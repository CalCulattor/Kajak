package main

import (
	"regexp"
	"time"
)

// Typy przeszkód są takie same jak w aplikacji (enum ObstacleType).
var obstacleTypes = map[string]bool{
	"STRAINER":   true,
	"WEIR":       true,
	"LOW_BRIDGE": true,
	"ROCK_SIEVE": true,
	"PORTAGE":    true,
	"OTHER":      true,
}

// Odcinki są identyfikowane stabilnym kluczem tekstowym (np. "dunajec-sromowce-szczawnica"),
// bo lokalne identyfikatory z bazy telefonu różnią się między urządzeniami.
var sectionKeyRe = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,63}$`)

// Typ rzeki i trudność są takie same jak w aplikacji (enumy RiverType i Difficulty).
var riverTypes = map[string]bool{"LOWLAND": true, "MOUNTAIN": true}

var difficulties = map[string]bool{
	"FLAT": true, "WW1": true, "WW2": true, "WW3": true, "WW4": true, "WW5": true,
}

// Route to trasa (odcinek rzeki) dodana przez użytkownika i widoczna dla wszystkich.
// Key jest nadawany przez serwer i jest stabilnym identyfikatorem odcinka
// (używają go przeszkody i spływy).
type Route struct {
	Key         string    `json:"key"`
	ClientID    string    `json:"client_id,omitempty"`
	RiverName   string    `json:"river_name"`
	Region      string    `json:"region"`
	RiverType   string    `json:"river_type"`
	Name        string    `json:"name"`
	LengthKm    float64   `json:"length_km"`
	Difficulty  string    `json:"difficulty"`
	PutIn       string    `json:"put_in"`
	TakeOut     string    `json:"take_out"`
	Lat         float64   `json:"lat"`
	Lon         float64   `json:"lon"`
	StationName string    `json:"station_name,omitempty"`
	Description string    `json:"description"`
	CreatedAt   time.Time `json:"created_at"`
}

// Takie same reguły jak w aplikacji (ObstacleRules): przeszkoda znika po co najmniej
// dwóch zgłoszeniach usunięcia, gdy jest ich więcej niż potwierdzeń.
const removalVotesNeeded = 2

type Obstacle struct {
	ID             int64     `json:"id"`
	ClientID       string    `json:"client_id,omitempty"`
	SectionKey     string    `json:"section_key"`
	Type           string    `json:"type"`
	Description    string    `json:"description"`
	Lat            *float64  `json:"lat,omitempty"`
	Lon            *float64  `json:"lon,omitempty"`
	ReportedAt     time.Time `json:"reported_at"`
	LastVerifiedAt time.Time `json:"last_verified_at"`
	Confirmations  int       `json:"confirmations"`
	RemovalVotes   int       `json:"removal_votes"`
}

func (o Obstacle) IsActive() bool {
	return !(o.RemovalVotes >= removalVotesNeeded && o.RemovalVotes > o.Confirmations)
}

// ObstacleView to odpowiedź API: przeszkoda wraz z wyliczoną aktywnością.
type ObstacleView struct {
	Obstacle
	Active bool `json:"active"`
}

func viewOf(o Obstacle) ObstacleView { return ObstacleView{Obstacle: o, Active: o.IsActive()} }

type Trip struct {
	ID         int64     `json:"id"`
	Title      string    `json:"title"`
	SectionKey *string   `json:"section_key,omitempty"`
	StartDate  string    `json:"start_date"` // YYYY-MM-DD
	Overnight  bool      `json:"overnight"`
	Organizer  string    `json:"organizer"` // w odpowiedziach: pierwszy aktualny organizator (twórca, jeśli nadal nim jest)
	Organizers []string  `json:"organizers,omitempty"`
	Notes      string    `json:"notes"`
	CreatedAt  time.Time `json:"created_at"`
}

type Participant struct {
	ID         int64  `json:"id"`
	TripID     int64  `json:"trip_id"`
	Name       string `json:"name"`
	CarSeats   int    `json:"car_seats"`
	NeedsKayak bool   `json:"needs_kayak"`
	// IsOrganizer: organizator może usuwać spływ i mianować kolejnych organizatorów.
	IsOrganizer bool `json:"is_organizer"`
}

type GearItem struct {
	ID         int64   `json:"id"`
	TripID     int64   `json:"trip_id"`
	Name       string  `json:"name"`
	AssignedTo *string `json:"assigned_to"`
	Packed     bool    `json:"packed"`
}

type CheckIn struct {
	ID         int64     `json:"id"`
	ClientID   string    `json:"client_id,omitempty"`
	TripID     int64     `json:"trip_id"`
	PersonName string    `json:"person_name"`
	Lat        float64   `json:"lat"`
	Lon        float64   `json:"lon"`
	FixAt      time.Time `json:"fix_at"`
	CreatedAt  time.Time `json:"created_at"`
	NeedsHelp  bool      `json:"needs_help"`
}

// TripDetail to pełny widok spływu.
type TripDetail struct {
	Trip         Trip          `json:"trip"`
	Participants []Participant `json:"participants"`
	Gear         []GearItem    `json:"gear"`
	CheckIns     []CheckIn     `json:"check_ins"`
}
