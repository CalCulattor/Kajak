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

var timeRe = regexp.MustCompile(`^([01]\d|2[0-3]):[0-5]\d$`)
var dateRe = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)

type Server struct {
	store         *Store
	hub           *hub     // powiadomienia na żywo (SSE)
	loginLimit    *limiter // nieudane logowania na nazwę użytkownika
	registerLimit *limiter // rejestracje ogółem (ochrona przed zalewaniem pliku danych)
}

func NewServer(store *Store) *Server {
	return &Server{
		store:         store,
		hub:           newHub(),
		loginLimit:    newLimiter(8, 10*time.Minute),
		registerLimit: newLimiter(30, time.Hour),
	}
}

// Handler zwraca router z wszystkimi trasami API.
func (srv *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /api/health", srv.health)

	mux.HandleFunc("POST /api/register", srv.register)
	mux.HandleFunc("POST /api/login", srv.login)
	mux.HandleFunc("POST /api/logout", srv.logout)
	mux.HandleFunc("GET /api/me", srv.me)
	mux.HandleFunc("GET /api/events", srv.events)

	mux.HandleFunc("GET /api/routes", srv.listRoutes)
	mux.HandleFunc("POST /api/routes", srv.createRoute)
	mux.HandleFunc("GET /api/routes/{key}", srv.getRoute)

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
	mux.HandleFunc("POST /api/trips/{id}/participants/{pid}/organizer", srv.promoteParticipant)

	mux.HandleFunc("POST /api/trips/{id}/gear", srv.createGear)
	mux.HandleFunc("PATCH /api/trips/{id}/gear/{gid}", srv.patchGear)
	mux.HandleFunc("DELETE /api/trips/{id}/gear/{gid}", srv.deleteGear)

	mux.HandleFunc("GET /api/trips/{id}/checkins", srv.listCheckIns)
	mux.HandleFunc("POST /api/trips/{id}/checkins", srv.createCheckIn)
	mux.HandleFunc("PATCH /api/trips/{id}/checkins/{cid}", srv.patchCheckIn)

	mux.HandleFunc("PUT /api/trips/{id}/location", srv.putLocation)
	mux.HandleFunc("DELETE /api/trips/{id}/location", srv.deleteLocation)
	mux.HandleFunc("GET /api/trips/{id}/locations", srv.listLocations)

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
	if errors.Is(err, errOwnerLeft) {
		writeError(w, http.StatusForbidden, "jesteś jedynym organizatorem – mianuj kolejnego organizatora albo usuń spływ")
		return
	}
	if errors.Is(err, errNotOrganizer) {
		writeError(w, http.StatusForbidden, "tylko organizator może to zrobić")
		return
	}
	if errors.Is(err, errForbidden) {
		writeError(w, http.StatusForbidden, "możesz usunąć tylko siebie")
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

// Unwrap pozwala http.ResponseController dotrzeć do oryginalnego ResponseWriter (Flush, deadline).
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// ---------------------------------------------------------------- health

func (srv *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// ---------------------------------------------------------------- trasy

func (srv *Server) listRoutes(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, srv.store.ListRoutes())
}

func (srv *Server) getRoute(w http.ResponseWriter, r *http.Request) {
	rt, err := srv.store.GetRoute(r.PathValue("key"))
	if err != nil {
		storeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, rt)
}

type createRouteRequest struct {
	ClientID    string  `json:"client_id"`
	RiverName   string  `json:"river_name"`
	Region      string  `json:"region"`
	RiverType   string  `json:"river_type"`
	Name        string  `json:"name"`
	LengthKm    float64 `json:"length_km"`
	Difficulty  string  `json:"difficulty"`
	PutIn       string  `json:"put_in"`
	TakeOut     string  `json:"take_out"`
	Lat         float64 `json:"lat"`
	Lon         float64 `json:"lon"`
	StationName string  `json:"station_name"`
	Description string  `json:"description"`
}

func (srv *Server) createRoute(w http.ResponseWriter, r *http.Request) {
	if _, ok := srv.currentUser(w, r); !ok {
		return
	}
	var req createRouteRequest
	if !decode(w, r, &req) {
		return
	}
	req.RiverName = strings.TrimSpace(req.RiverName)
	req.Region = strings.TrimSpace(req.Region)
	req.Name = strings.TrimSpace(req.Name)
	req.PutIn = strings.TrimSpace(req.PutIn)
	req.TakeOut = strings.TrimSpace(req.TakeOut)
	req.StationName = strings.TrimSpace(req.StationName)
	req.Description = strings.TrimSpace(req.Description)

	switch {
	case req.RiverName == "" || !validText(req.RiverName, 80):
		writeError(w, http.StatusBadRequest, "river_name jest wymagane (do 80 znaków)")
	case req.Name == "" || !validText(req.Name, 120):
		writeError(w, http.StatusBadRequest, "name jest wymagane (do 120 znaków)")
	case !validText(req.Region, 80) || !validText(req.PutIn, 120) || !validText(req.TakeOut, 120) ||
		!validText(req.StationName, 80):
		writeError(w, http.StatusBadRequest, "region, put_in, take_out lub station_name jest za długie")
	case !validText(req.Description, maxTextRunes):
		writeError(w, http.StatusBadRequest, "description jest za długie")
	case !riverTypes[req.RiverType]:
		writeError(w, http.StatusBadRequest, "nieznany river_type: "+req.RiverType)
	case !difficulties[req.Difficulty]:
		writeError(w, http.StatusBadRequest, "nieznana difficulty: "+req.Difficulty)
	case math.IsNaN(req.LengthKm) || req.LengthKm < 0 || req.LengthKm > 1000:
		writeError(w, http.StatusBadRequest, "length_km musi być w zakresie 0-1000")
	case !validLatLon(req.Lat, req.Lon):
		writeError(w, http.StatusBadRequest, "współrzędne poza zakresem")
	case len(req.ClientID) > maxClientIDLen:
		writeError(w, http.StatusBadRequest, "client_id jest za długi")
	default:
		rt, created, err := srv.store.AddRoute(Route{
			ClientID: req.ClientID, RiverName: req.RiverName, Region: req.Region,
			RiverType: req.RiverType, Name: req.Name, LengthKm: req.LengthKm,
			Difficulty: req.Difficulty, PutIn: req.PutIn, TakeOut: req.TakeOut,
			Lat: req.Lat, Lon: req.Lon, StationName: req.StationName, Description: req.Description,
		})
		if err != nil {
			storeError(w, err)
			return
		}
		status := http.StatusCreated
		if !created {
			status = http.StatusOK
		}
		if created {
			srv.routesChanged()
		}
		writeJSON(w, status, rt)
	}
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
	if _, ok := srv.currentUser(w, r); !ok {
		return
	}
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
	if created {
		srv.obstaclesChanged(key)
	}
	writeJSON(w, status, viewOf(o))
}

func (srv *Server) voteObstacle(confirm bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := srv.currentUser(w, r); !ok {
			return
		}
		id, ok := pathID(w, r, "id")
		if !ok {
			return
		}
		o, err := srv.store.VoteObstacle(id, confirm)
		if err != nil {
			storeError(w, err)
			return
		}
		srv.obstaclesChanged(o.SectionKey)
		writeJSON(w, http.StatusOK, viewOf(o))
	}
}

// ---------------------------------------------------------------- spływy

// Organizatorem spływu jest zawsze zalogowany użytkownik, który go tworzy.
type createTripRequest struct {
	Title      string  `json:"title"`
	SectionKey *string `json:"section_key"`
	StartDate  string  `json:"start_date"`
	StartTime  string  `json:"start_time"`
	Overnight  bool    `json:"overnight"`
	Notes      string  `json:"notes"`
}

// requireMember wymaga zalogowania i bycia uczestnikiem spływu (organizator jest uczestnikiem).
func (srv *Server) requireMember(w http.ResponseWriter, r *http.Request, tripID int64) (string, bool) {
	user, ok := srv.currentUser(w, r)
	if !ok {
		return "", false
	}
	a := srv.store.Access(tripID, user)
	switch {
	case !a.Exists:
		writeError(w, http.StatusNotFound, "nie znaleziono")
	case !a.Member:
		writeError(w, http.StatusForbidden, "nie jesteś uczestnikiem tego spływu")
	default:
		return user, true
	}
	return "", false
}

func (srv *Server) listTrips(w http.ResponseWriter, r *http.Request) {
	if _, ok := srv.currentUser(w, r); !ok {
		return
	}
	writeJSON(w, http.StatusOK, srv.store.ListTrips())
}

// nowUTC jest podmienialne w testach.
var nowUTC = func() time.Time { return time.Now().UTC() }

func (srv *Server) createTrip(w http.ResponseWriter, r *http.Request) {
	user, ok := srv.currentUser(w, r)
	if !ok {
		return
	}
	var req createTripRequest
	if !decode(w, r, &req) {
		return
	}
	req.Title = strings.TrimSpace(req.Title)
	req.Notes = strings.TrimSpace(req.Notes)

	switch {
	case req.Title == "" || !validText(req.Title, maxTitleRunes):
		writeError(w, http.StatusBadRequest, "title jest wymagany (do 120 znaków)")
		return
	case !validText(req.Notes, maxTextRunes):
		writeError(w, http.StatusBadRequest, "notes są za długie")
		return
	case !dateRe.MatchString(req.StartDate):
		writeError(w, http.StatusBadRequest, "start_date musi mieć format RRRR-MM-DD")
		return
	}
	if req.StartTime != "" && !timeRe.MatchString(req.StartTime) {
		writeError(w, http.StatusBadRequest, "start_time musi mieć format GG:MM")
		return
	}
	day, err := time.Parse("2006-01-02", req.StartDate)
	if err != nil {
		writeError(w, http.StatusBadRequest, "start_date nie jest poprawną datą")
		return
	}
	// Dzień tolerancji, żeby różnica stref czasowych nie odrzucała dzisiejszych spływów.
	if day.Before(nowUTC().AddDate(0, 0, -1).Truncate(24 * time.Hour)) {
		writeError(w, http.StatusBadRequest, "start_date nie może być z przeszłości")
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
		StartTime:  req.StartTime,
		Overnight:  req.Overnight,
		Organizer:  user,
		Notes:      req.Notes,
	})
	if err != nil {
		storeError(w, err)
		return
	}
	// Organizator jest pierwszym uczestnikiem, tak jak w aplikacji.
	if _, err := srv.store.AddParticipant(t.ID, Participant{Name: user, IsOrganizer: true}); err != nil {
		storeError(w, err)
		return
	}
	srv.tripsChanged()
	writeJSON(w, http.StatusCreated, t)
}

func (srv *Server) getTrip(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if _, ok := srv.requireMember(w, r, id); !ok {
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
	user, ok := srv.currentUser(w, r)
	if !ok {
		return
	}
	switch a := srv.store.Access(id, user); {
	case !a.Exists:
		writeError(w, http.StatusNotFound, "nie znaleziono")
		return
	case !a.Organizer:
		writeError(w, http.StatusForbidden, "spływ może usunąć tylko organizator")
		return
	}
	if err := srv.store.DeleteTrip(id); err != nil {
		storeError(w, err)
		return
	}
	srv.tripChanged(id)
	srv.tripsChanged()
	w.WriteHeader(http.StatusNoContent)
}

// ---------------------------------------------------------------- uczestnicy

// Uczestnika można dodać tylko jako siebie: nazwa pochodzi z konta. Pole name jest
// opcjonalne i musi być zgodne z kontem (inaczej 403). Powtórne wywołanie aktualizuje dane.
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
	user, ok := srv.currentUser(w, r)
	if !ok {
		return
	}
	var req createParticipantRequest
	if !decode(w, r, &req) {
		return
	}
	if name := strings.TrimSpace(req.Name); name != "" && !strings.EqualFold(name, user) {
		writeError(w, http.StatusForbidden, "możesz dodać tylko siebie")
		return
	}
	if req.CarSeats < 0 || req.CarSeats > maxCarSeats {
		writeError(w, http.StatusBadRequest, "car_seats musi być w zakresie 0-99")
		return
	}
	p, created, err := srv.store.JoinTrip(tripID, user, req.CarSeats, req.NeedsKayak)
	if err != nil {
		storeError(w, err)
		return
	}
	status := http.StatusCreated
	if !created {
		status = http.StatusOK
	}
	srv.tripChanged(tripID)
	writeJSON(w, status, p)
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
	user, ok := srv.currentUser(w, r)
	if !ok {
		return
	}
	if err := srv.store.LeaveTrip(tripID, pid, user); err != nil {
		storeError(w, err)
		return
	}
	srv.tripChanged(tripID)
	w.WriteHeader(http.StatusNoContent)
}

// promoteParticipant: organizator mianuje uczestnika (też siebie nie – to już jest organizatorem) organizatorem.
func (srv *Server) promoteParticipant(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	pid, ok := pathID(w, r, "pid")
	if !ok {
		return
	}
	user, ok := srv.currentUser(w, r)
	if !ok {
		return
	}
	p, err := srv.store.PromoteOrganizer(tripID, pid, user)
	if err != nil {
		storeError(w, err)
		return
	}
	srv.tripChanged(tripID)
	srv.tripsChanged()
	writeJSON(w, http.StatusOK, p)
}

// ---------------------------------------------------------------- wyposażenie

type createGearRequest struct {
	Name       string  `json:"name"`
	AssignedTo *string `json:"assigned_to"`
}

// validAssignee sprawdza, że przypisanie wskazuje uczestnika spływu (albo brak przypisania).
func (srv *Server) validAssignee(w http.ResponseWriter, tripID int64, assignee *string) (*string, bool) {
	if assignee == nil {
		return nil, true
	}
	for _, name := range srv.store.ParticipantNames(tripID) {
		if strings.EqualFold(name, strings.TrimSpace(*assignee)) {
			return &name, true
		}
	}
	writeError(w, http.StatusBadRequest, "assigned_to musi być uczestnikiem spływu")
	return nil, false
}

func (srv *Server) createGear(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if _, ok := srv.requireMember(w, r, tripID); !ok {
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
	assignee, ok := srv.validAssignee(w, tripID, req.AssignedTo)
	if !ok {
		return
	}
	g, err := srv.store.AddGear(tripID, GearItem{Name: req.Name, AssignedTo: assignee})
	if err != nil {
		storeError(w, err)
		return
	}
	srv.tripChanged(tripID)
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
	if _, ok := srv.requireMember(w, r, tripID); !ok {
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
	if patch.AssignedToSet {
		assignee, ok := srv.validAssignee(w, tripID, patch.AssignedTo)
		if !ok {
			return
		}
		patch.AssignedTo = assignee
	}
	g, err := srv.store.UpdateGear(tripID, gid, patch)
	if err != nil {
		storeError(w, err)
		return
	}
	srv.tripChanged(tripID)
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
	if _, ok := srv.requireMember(w, r, tripID); !ok {
		return
	}
	if err := srv.store.DeleteGear(tripID, gid); err != nil {
		storeError(w, err)
		return
	}
	srv.tripChanged(tripID)
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
	if _, ok := srv.requireMember(w, r, tripID); !ok {
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
	user, ok := srv.requireMember(w, r, tripID)
	if !ok {
		return
	}
	var req createCheckInRequest
	if !decode(w, r, &req) {
		return
	}
	// Zameldować można tylko siebie; person_name jest opcjonalne i musi zgadzać się z kontem.
	if name := strings.TrimSpace(req.PersonName); name != "" && !strings.EqualFold(name, user) {
		writeError(w, http.StatusForbidden, "możesz zameldować tylko siebie")
		return
	}
	req.PersonName = user
	switch {
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
	if created {
		srv.tripChanged(tripID)
	}
	writeJSON(w, status, c)
}

type patchCheckInRequest struct {
	NeedsHelp *bool `json:"needs_help"`
}

// patchCheckIn pozwala autorowi zameldowania odwołać (lub ponowić) wezwanie pomocy.
// Cudzego wezwania nie może zmienić nikt – także organizator.
func (srv *Server) patchCheckIn(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	cid, ok := pathID(w, r, "cid")
	if !ok {
		return
	}
	user, ok := srv.requireMember(w, r, tripID)
	if !ok {
		return
	}
	var req patchCheckInRequest
	if !decode(w, r, &req) {
		return
	}
	if req.NeedsHelp == nil {
		writeError(w, http.StatusBadRequest, "needs_help jest wymagane")
		return
	}
	c, err := srv.store.SetCheckInHelp(tripID, cid, user, *req.NeedsHelp)
	if err != nil {
		storeError(w, err)
		return
	}
	srv.tripChanged(tripID)
	writeJSON(w, http.StatusOK, c)
}
