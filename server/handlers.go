package main

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"math"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	maxBodyBytes   = 1 << 20 // 1 MiB
	maxTitleRunes  = 120
	maxNameRunes   = 80
	maxTextRunes   = 1000
	maxClientIDLen = 64
	maxCarSeats    = 99
)

var dateRe = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)

type Server struct {
	store *Store
}

func NewServer(store *Store) *Server { return &Server{store: store} }

// Handler zwraca router z wszystkimi trasami API.
func (srv *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /api/health", srv.health)

	mux.HandleFunc("GET /api/sections/{key}/obstacles", srv.listObstacles)
	mux.HandleFunc("POST /api/sections/{key}/obstacles", srv.createObstacle)
	mux.HandleFunc("POST /api/obstacles/{id}/confirm", srv.voteObstacle(true))
	mux.HandleFunc("POST /api/obstacles/{id}/remove-vote", srv.voteObstacle(false))

	mux.HandleFunc("GET /api/trips", srv.listTrips)
	mux.HandleFunc("POST /api/trips", srv.createTrip)
	mux.HandleFunc("GET /api/trips/{id}", srv.getTrip)
	mux.HandleFunc("DELETE /api/trips/{id}", srv.deleteTrip)

	mux.HandleFunc("POST /api/trips/{id}/participants", srv.createParticipant)
	mux.HandleFunc("DELETE /api/trips/{id}/participants/{pid}", srv.deleteParticipant)

	mux.HandleFunc("POST /api/trips/{id}/gear", srv.createGear)
	mux.HandleFunc("PATCH /api/trips/{id}/gear/{gid}", srv.patchGear)
	mux.HandleFunc("DELETE /api/trips/{id}/gear/{gid}", srv.deleteGear)

	mux.HandleFunc("GET /api/trips/{id}/checkins", srv.listCheckIns)
	mux.HandleFunc("POST /api/trips/{id}/checkins", srv.createCheckIn)

	return logRequests(mux)
}

// ---------------------------------------------------------------- pomocnicze

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v != nil {
		if err := json.NewEncoder(w).Encode(v); err != nil {
			log.Printf("błąd zapisu odpowiedzi: %v", err)
		}
	}
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

// storeError zamienia błąd magazynu na odpowiedź HTTP.
func storeError(w http.ResponseWriter, err error) {
	if errors.Is(err, errNotFound) {
		writeError(w, http.StatusNotFound, "nie znaleziono")
		return
	}
	log.Printf("błąd magazynu: %v", err)
	writeError(w, http.StatusInternalServerError, "błąd wewnętrzny serwera")
}

func decode(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeError(w, http.StatusRequestEntityTooLarge, "zbyt duże żądanie")
		} else {
			writeError(w, http.StatusBadRequest, "niepoprawny JSON: "+err.Error())
		}
		return false
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "niepoprawny JSON: dane po końcu obiektu")
		return false
	}
	return true
}

func pathID(w http.ResponseWriter, r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	if err != nil || id < 1 {
		writeError(w, http.StatusBadRequest, "niepoprawny identyfikator: "+name)
		return 0, false
	}
	return id, true
}

func validText(s string, maxRunes int) bool {
	return utf8.RuneCountInString(s) <= maxRunes
}

func validLatLon(lat, lon float64) bool {
	return !math.IsNaN(lat) && !math.IsNaN(lon) &&
		lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		log.Printf("%s %s -> %d (%s)", r.Method, r.URL.Path, rec.status, time.Since(start).Round(time.Millisecond))
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// ---------------------------------------------------------------- health

func (srv *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// ---------------------------------------------------------------- przeszkody

func (srv *Server) listObstacles(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if !sectionKeyRe.MatchString(key) {
		writeError(w, http.StatusBadRequest, "niepoprawny klucz odcinka")
		return
	}
	includeInactive := r.URL.Query().Get("include_inactive") == "true"
	list := srv.store.ListObstacles(key, includeInactive)
	views := make([]ObstacleView, len(list))
	for i, o := range list {
		views[i] = viewOf(o)
	}
	writeJSON(w, http.StatusOK, views)
}

type createObstacleRequest struct {
	ClientID    string   `json:"client_id"`
	Type        string   `json:"type"`
	Description string   `json:"description"`
	Lat         *float64 `json:"lat"`
	Lon         *float64 `json:"lon"`
}

func (srv *Server) createObstacle(w http.ResponseWriter, r *http.Request) {
	key := r.PathValue("key")
	if !sectionKeyRe.MatchString(key) {
		writeError(w, http.StatusBadRequest, "niepoprawny klucz odcinka")
		return
	}
	var req createObstacleRequest
	if !decode(w, r, &req) {
		return
	}
	if !obstacleTypes[req.Type] {
		writeError(w, http.StatusBadRequest, "nieznany typ przeszkody: "+req.Type)
		return
	}
	req.Description = strings.TrimSpace(req.Description)
	if !validText(req.Description, maxTextRunes) {
		writeError(w, http.StatusBadRequest, "opis jest za długi")
		return
	}
	if len(req.ClientID) > maxClientIDLen {
		writeError(w, http.StatusBadRequest, "client_id jest za długi")
		return
	}
	if (req.Lat == nil) != (req.Lon == nil) {
		writeError(w, http.StatusBadRequest, "podaj lat i lon razem albo wcale")
		return
	}
	if req.Lat != nil && !validLatLon(*req.Lat, *req.Lon) {
		writeError(w, http.StatusBadRequest, "współrzędne poza zakresem")
		return
	}

	o, created, err := srv.store.AddObstacle(Obstacle{
		ClientID:    req.ClientID,
		SectionKey:  key,
		Type:        req.Type,
		Description: req.Description,
		Lat:         req.Lat,
		Lon:         req.Lon,
	})
	if err != nil {
		storeError(w, err)
		return
	}
	status := http.StatusCreated
	if !created {
		status = http.StatusOK
	}
	writeJSON(w, status, viewOf(o))
}

func (srv *Server) voteObstacle(confirm bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, ok := pathID(w, r, "id")
		if !ok {
			return
		}
		o, err := srv.store.VoteObstacle(id, confirm)
		if err != nil {
			storeError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, viewOf(o))
	}
}

// ---------------------------------------------------------------- spływy

type createTripRequest struct {
	Title      string  `json:"title"`
	SectionKey *string `json:"section_key"`
	StartDate  string  `json:"start_date"`
	Overnight  bool    `json:"overnight"`
	Organizer  string  `json:"organizer"`
	Notes      string  `json:"notes"`
}

func (srv *Server) listTrips(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, srv.store.ListTrips())
}

func (srv *Server) createTrip(w http.ResponseWriter, r *http.Request) {
	var req createTripRequest
	if !decode(w, r, &req) {
		return
	}
	req.Title = strings.TrimSpace(req.Title)
	req.Organizer = strings.TrimSpace(req.Organizer)
	req.Notes = strings.TrimSpace(req.Notes)

	switch {
	case req.Title == "" || !validText(req.Title, maxTitleRunes):
		writeError(w, http.StatusBadRequest, "title jest wymagany (do 120 znaków)")
		return
	case req.Organizer == "" || !validText(req.Organizer, maxNameRunes):
		writeError(w, http.StatusBadRequest, "organizer jest wymagany (do 80 znaków)")
		return
	case !validText(req.Notes, maxTextRunes):
		writeError(w, http.StatusBadRequest, "notes są za długie")
		return
	case !dateRe.MatchString(req.StartDate):
		writeError(w, http.StatusBadRequest, "start_date musi mieć format RRRR-MM-DD")
		return
	}
	if _, err := time.Parse("2006-01-02", req.StartDate); err != nil {
		writeError(w, http.StatusBadRequest, "start_date nie jest poprawną datą")
		return
	}
	if req.SectionKey != nil && !sectionKeyRe.MatchString(*req.SectionKey) {
		writeError(w, http.StatusBadRequest, "niepoprawny section_key")
		return
	}

	t, err := srv.store.AddTrip(Trip{
		Title:      req.Title,
		SectionKey: req.SectionKey,
		StartDate:  req.StartDate,
		Overnight:  req.Overnight,
		Organizer:  req.Organizer,
		Notes:      req.Notes,
	})
	if err != nil {
		storeError(w, err)
		return
	}
	// Organizator jest pierwszym uczestnikiem, tak jak w aplikacji.
	if _, err := srv.store.AddParticipant(t.ID, Participant{Name: req.Organizer}); err != nil {
		storeError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, t)
}

func (srv *Server) getTrip(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	d, err := srv.store.GetTrip(id)
	if err != nil {
		storeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

func (srv *Server) deleteTrip(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if err := srv.store.DeleteTrip(id); err != nil {
		storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------------------------------------------------------------- uczestnicy

type createParticipantRequest struct {
	Name       string `json:"name"`
	CarSeats   int    `json:"car_seats"`
	NeedsKayak bool   `json:"needs_kayak"`
}

func (srv *Server) createParticipant(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req createParticipantRequest
	if !decode(w, r, &req) {
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" || !validText(req.Name, maxNameRunes) {
		writeError(w, http.StatusBadRequest, "name jest wymagane (do 80 znaków)")
		return
	}
	if req.CarSeats < 0 || req.CarSeats > maxCarSeats {
		writeError(w, http.StatusBadRequest, "car_seats musi być w zakresie 0-99")
		return
	}
	p, err := srv.store.AddParticipant(tripID, Participant{
		Name: req.Name, CarSeats: req.CarSeats, NeedsKayak: req.NeedsKayak,
	})
	if err != nil {
		storeError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, p)
}

func (srv *Server) deleteParticipant(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	pid, ok := pathID(w, r, "pid")
	if !ok {
		return
	}
	if err := srv.store.DeleteParticipant(tripID, pid); err != nil {
		storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------------------------------------------------------------- wyposażenie

type createGearRequest struct {
	Name       string  `json:"name"`
	AssignedTo *string `json:"assigned_to"`
}

func (srv *Server) createGear(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req createGearRequest
	if !decode(w, r, &req) {
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" || !validText(req.Name, maxNameRunes) {
		writeError(w, http.StatusBadRequest, "name jest wymagane (do 80 znaków)")
		return
	}
	if req.AssignedTo != nil && !validText(*req.AssignedTo, maxNameRunes) {
		writeError(w, http.StatusBadRequest, "assigned_to jest za długie")
		return
	}
	g, err := srv.store.AddGear(tripID, GearItem{Name: req.Name, AssignedTo: req.AssignedTo})
	if err != nil {
		storeError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, g)
}

func (srv *Server) patchGear(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	gid, ok := pathID(w, r, "gid")
	if !ok {
		return
	}

	// Odróżniamy brak pola od jawnego null (czyszczenie przypisania).
	var raw map[string]json.RawMessage
	if !decode(w, r, &raw) {
		return
	}
	var patch GearPatch
	for k, v := range raw {
		switch k {
		case "assigned_to":
			patch.AssignedToSet = true
			if string(v) != "null" {
				var s string
				if err := json.Unmarshal(v, &s); err != nil || !validText(s, maxNameRunes) {
					writeError(w, http.StatusBadRequest, "assigned_to musi być tekstem do 80 znaków lub null")
					return
				}
				patch.AssignedTo = &s
			}
		case "packed":
			var b bool
			if err := json.Unmarshal(v, &b); err != nil {
				writeError(w, http.StatusBadRequest, "packed musi być true albo false")
				return
			}
			patch.Packed = &b
		default:
			writeError(w, http.StatusBadRequest, "nieznane pole: "+k)
			return
		}
	}
	if !patch.AssignedToSet && patch.Packed == nil {
		writeError(w, http.StatusBadRequest, "podaj assigned_to lub packed")
		return
	}
	g, err := srv.store.UpdateGear(tripID, gid, patch)
	if err != nil {
		storeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, g)
}

func (srv *Server) deleteGear(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	gid, ok := pathID(w, r, "gid")
	if !ok {
		return
	}
	if err := srv.store.DeleteGear(tripID, gid); err != nil {
		storeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------------------------------------------------------------- zameldowania

type createCheckInRequest struct {
	ClientID   string     `json:"client_id"`
	PersonName string     `json:"person_name"`
	Lat        *float64   `json:"lat"`
	Lon        *float64   `json:"lon"`
	FixAt      *time.Time `json:"fix_at"`
	NeedsHelp  bool       `json:"needs_help"`
}

func (srv *Server) listCheckIns(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	list, err := srv.store.ListCheckIns(tripID)
	if err != nil {
		storeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, list)
}

func (srv *Server) createCheckIn(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req createCheckInRequest
	if !decode(w, r, &req) {
		return
	}
	req.PersonName = strings.TrimSpace(req.PersonName)
	switch {
	case req.PersonName == "" || !validText(req.PersonName, maxNameRunes):
		writeError(w, http.StatusBadRequest, "person_name jest wymagane (do 80 znaków)")
		return
	case req.Lat == nil || req.Lon == nil:
		writeError(w, http.StatusBadRequest, "lat i lon są wymagane")
		return
	case !validLatLon(*req.Lat, *req.Lon):
		writeError(w, http.StatusBadRequest, "współrzędne poza zakresem")
		return
	case len(req.ClientID) > maxClientIDLen:
		writeError(w, http.StatusBadRequest, "client_id jest za długi")
		return
	}

	// fix_at to czas pomiaru GPS; gdy go brak, przyjmujemy chwilę odebrania żądania.
	fixAt := srv.store.now()
	if req.FixAt != nil {
		fixAt = req.FixAt.UTC()
	}

	c, created, err := srv.store.AddCheckIn(tripID, CheckIn{
		ClientID:   req.ClientID,
		PersonName: req.PersonName,
		Lat:        *req.Lat,
		Lon:        *req.Lon,
		FixAt:      fixAt,
		NeedsHelp:  req.NeedsHelp,
	})
	if err != nil {
		storeError(w, err)
		return
	}
	status := http.StatusCreated
	if !created {
		status = http.StatusOK
	}
	writeJSON(w, status, c)
}
