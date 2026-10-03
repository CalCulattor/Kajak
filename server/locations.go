package main

import (
	"net/http"
	"sort"
	"strings"
	"time"
)

// Pozycje uczestników na żywo. Trzymamy tylko ostatnią pozycję każdego użytkownika w pamięci
// (nie zapisujemy ich do pliku danych) i zapominamy je po liveLocationTTL – to dane chwilowe.

const liveLocationTTL = 15 * time.Minute

// LiveLocation to ostatnia znana pozycja uczestnika spływu.
type LiveLocation struct {
	Lat       float64   `json:"lat"`
	Lon       float64   `json:"lon"`
	FixAt     time.Time `json:"fix_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

// LocationView to pozycja uczestnika wraz z informacją, czy prosi o pomoc.
type LocationView struct {
	Username  string    `json:"username"`
	Lat       float64   `json:"lat"`
	Lon       float64   `json:"lon"`
	FixAt     time.Time `json:"fix_at"`
	UpdatedAt time.Time `json:"updated_at"`
	NeedsHelp bool      `json:"needs_help"`
	// HelpCheckInID to zameldowanie z prośbą o pomoc (do jego odwołania); 0, gdy nie ma prośby.
	HelpCheckInID int64 `json:"help_check_in_id,omitempty"`
}

// SetLiveLocation zapamiętuje ostatnią pozycję użytkownika w spływie.
func (st *Store) SetLiveLocation(tripID int64, user string, loc LiveLocation) error {
	st.mu.RLock()
	exists := tripExists(&st.s, tripID)
	st.mu.RUnlock()
	if !exists {
		return errNotFound
	}
	st.liveMu.Lock()
	defer st.liveMu.Unlock()
	if st.live == nil {
		st.live = map[int64]map[string]LiveLocation{}
	}
	if st.live[tripID] == nil {
		st.live[tripID] = map[string]LiveLocation{}
	}
	st.live[tripID][strings.ToLower(user)] = loc
	return nil
}

// ListLocations zwraca aktualne pozycje uczestników. Prośba o pomoc pochodzi z najnowszego
// zameldowania danej osoby (jeśli ma needs_help, osoba widnieje na liście nawet bez świeżej pozycji).
func (st *Store) ListLocations(tripID int64) ([]LocationView, error) {
	now := st.now()
	st.mu.RLock()
	if !tripExists(&st.s, tripID) {
		st.mu.RUnlock()
		return nil, errNotFound
	}
	latest := map[string]CheckIn{} // najnowsze zameldowanie każdego użytkownika
	names := map[string]string{}   // klucz małymi literami -> nazwa do wyświetlenia
	for _, c := range st.s.CheckIns {
		if c.TripID != tripID {
			continue
		}
		key := strings.ToLower(c.PersonName)
		if cur, ok := latest[key]; !ok || c.ID > cur.ID {
			latest[key] = c
		}
		names[key] = c.PersonName
	}
	for _, p := range st.s.Participants {
		if p.TripID == tripID {
			names[strings.ToLower(p.Name)] = p.Name
		}
	}
	st.mu.RUnlock()

	st.liveMu.Lock()
	live := map[string]LiveLocation{}
	for key, loc := range st.live[tripID] {
		if now.Sub(loc.UpdatedAt) > liveLocationTTL {
			delete(st.live[tripID], key)
			continue
		}
		live[key] = loc
	}
	st.liveMu.Unlock()

	out := []LocationView{}
	seen := map[string]bool{}
	for key, loc := range live {
		v := LocationView{Username: names[key], Lat: loc.Lat, Lon: loc.Lon, FixAt: loc.FixAt, UpdatedAt: loc.UpdatedAt}
		if v.Username == "" {
			v.Username = key
		}
		if c, ok := latest[key]; ok && c.NeedsHelp {
			v.NeedsHelp, v.HelpCheckInID = true, c.ID
		}
		out = append(out, v)
		seen[key] = true
	}
	for key, c := range latest {
		if seen[key] || !c.NeedsHelp {
			continue
		}
		// Prośba o pomoc bez świeżej pozycji: pokazujemy miejsce, z którego ją wysłano.
		out = append(out, LocationView{
			Username: c.PersonName, Lat: c.Lat, Lon: c.Lon, FixAt: c.FixAt, UpdatedAt: c.CreatedAt,
			NeedsHelp: true, HelpCheckInID: c.ID,
		})
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Username) < strings.ToLower(out[j].Username) })
	return out, nil
}

type putLocationRequest struct {
	Lat   *float64   `json:"lat"`
	Lon   *float64   `json:"lon"`
	FixAt *time.Time `json:"fix_at"`
}

// putLocation zapisuje własną pozycję użytkownika (tylko uczestnik spływu, tylko swoją).
func (srv *Server) putLocation(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	user, ok := srv.requireMember(w, r, tripID)
	if !ok {
		return
	}
	var req putLocationRequest
	if !decode(w, r, &req) {
		return
	}
	switch {
	case req.Lat == nil || req.Lon == nil:
		writeError(w, http.StatusBadRequest, "lat i lon są wymagane")
		return
	case !validLatLon(*req.Lat, *req.Lon):
		writeError(w, http.StatusBadRequest, "współrzędne poza zakresem")
		return
	}
	now := srv.store.now()
	fixAt := now
	if req.FixAt != nil && req.FixAt.Before(now.Add(time.Minute)) {
		fixAt = req.FixAt.UTC()
	}
	if err := srv.store.SetLiveLocation(tripID, user, LiveLocation{Lat: *req.Lat, Lon: *req.Lon, FixAt: fixAt, UpdatedAt: now}); err != nil {
		storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// listLocations zwraca pozycje uczestników spływu (tylko dla uczestników).
func (srv *Server) listLocations(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if _, ok := srv.requireMember(w, r, tripID); !ok {
		return
	}
	list, err := srv.store.ListLocations(tripID)
	if err != nil {
		storeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, list)
}
