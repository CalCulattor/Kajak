package main

import (
	"net/http"
	"sort"
	"strings"
	"time"
)

// Wezwania pomocy (SOS). Wezwanie to zameldowanie z needs_help. Widzą je:
//   - wszyscy uczestnicy spływu, w którym padło wezwanie (grupa),
//   - każdy, kto w tej chwili jest w trasie (udostępnia pozycję) w jakimkolwiek spływie na tej samej rzece –
//     bo w razie wypadku to właśnie osoby w pobliżu mogą pomóc najszybciej.
// Osoby spoza spływu dostają tylko to, co potrzebne do pomocy: kto, gdzie, na jakiej rzece.

// sosWindow to czas, przez który nieodwołane wezwanie jest uznawane za aktualne.
const sosWindow = 12 * time.Hour

// SOSAlert to aktywne wezwanie pomocy widoczne dla danego użytkownika.
type SOSAlert struct {
	CheckInID  int64     `json:"check_in_id"`
	PersonName string    `json:"person_name"`
	Lat        float64   `json:"lat"`
	Lon        float64   `json:"lon"`
	CreatedAt  time.Time `json:"created_at"`
	RiverKey   string    `json:"river_key,omitempty"`
	// Member: wezwanie z mojego spływu. Tylko wtedy podajemy identyfikator i tytuł spływu.
	Member    bool   `json:"member"`
	TripID    int64  `json:"trip_id,omitempty"`
	TripTitle string `json:"trip_title,omitempty"`
}

// riverKeyOf zwraca identyfikator rzeki dla klucza odcinka: slug nazwy rzeki z trasy dodanej w aplikacji,
// a dla odcinków wbudowanych (klucz „rzeka-odcinek”) pierwszy człon klucza.
func riverKeyOf(s *state, sectionKey string) string {
	if sectionKey == "" {
		return ""
	}
	for _, r := range s.Routes {
		if r.Key == sectionKey {
			return slugify(r.RiverName, 40)
		}
	}
	if i := strings.Index(sectionKey, "-"); i > 0 {
		return sectionKey[:i]
	}
	return sectionKey
}

// ActiveSOS zwraca aktualne wezwania pomocy widoczne dla użytkownika (poza jego własnymi).
func (st *Store) ActiveSOS(user string) []SOSAlert {
	now := st.now()
	st.liveMu.Lock()
	var liveTrips []int64
	for tripID, users := range st.live {
		if loc, ok := users[strings.ToLower(user)]; ok && now.Sub(loc.UpdatedAt) <= liveLocationTTL {
			liveTrips = append(liveTrips, tripID)
		}
	}
	st.liveMu.Unlock()

	st.mu.RLock()
	defer st.mu.RUnlock()

	myRivers := map[string]bool{}
	for _, id := range liveTrips {
		for _, t := range st.s.Trips {
			if t.ID == id && t.SectionKey != nil {
				if k := riverKeyOf(&st.s, *t.SectionKey); k != "" {
					myRivers[k] = true
				}
			}
		}
	}

	latest := map[string]CheckIn{} // najnowsze zameldowanie każdej osoby w każdym spływie
	for _, c := range st.s.CheckIns {
		key := itoa(c.TripID) + "/" + strings.ToLower(c.PersonName)
		if cur, ok := latest[key]; !ok || c.ID > cur.ID {
			latest[key] = c
		}
	}
	out := []SOSAlert{}
	for _, c := range latest {
		if !c.NeedsHelp || now.Sub(c.CreatedAt) > sosWindow || strings.EqualFold(c.PersonName, user) {
			continue
		}
		var trip *Trip
		for i := range st.s.Trips {
			if st.s.Trips[i].ID == c.TripID {
				trip = &st.s.Trips[i]
				break
			}
		}
		if trip == nil {
			continue
		}
		river := ""
		if trip.SectionKey != nil {
			river = riverKeyOf(&st.s, *trip.SectionKey)
		}
		member := accessIn(&st.s, c.TripID, user).Member
		if !member && !(river != "" && myRivers[river]) {
			continue
		}
		a := SOSAlert{
			CheckInID: c.ID, PersonName: c.PersonName, Lat: c.Lat, Lon: c.Lon, CreatedAt: c.CreatedAt,
			RiverKey: river, Member: member,
		}
		if member {
			a.TripID, a.TripTitle = trip.ID, trip.Title
		}
		out = append(out, a)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CheckInID < out[j].CheckInID })
	return out
}

// listSOS zwraca aktywne wezwania pomocy dla zalogowanego użytkownika.
func (srv *Server) listSOS(w http.ResponseWriter, r *http.Request) {
	user, ok := srv.currentUser(w, r)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, srv.store.ActiveSOS(user))
}
