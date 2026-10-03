package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

var errNotFound = errors.New("nie znaleziono")

type state struct {
	NextID       int64         `json:"next_id"`
	Obstacles    []Obstacle    `json:"obstacles"`
	Trips        []Trip        `json:"trips"`
	Participants []Participant `json:"participants"`
	Gear         []GearItem    `json:"gear"`
	CheckIns     []CheckIn     `json:"check_ins"`
}

// Store trzyma dane w pamięci i po każdej zmianie zapisuje je atomowo do pliku JSON.
// Wystarcza do pracy lokalnej i testów; przy większym ruchu zastąp go prawdziwą bazą.
type Store struct {
	mu   sync.RWMutex
	path string // pusty = tylko pamięć
	s    state
	now  func() time.Time
}

func OpenStore(path string) (*Store, error) {
	st := &Store{path: path, now: func() time.Time { return time.Now().UTC() }}
	st.s.NextID = 1
	if path == "" {
		return st, nil
	}
	data, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := json.Unmarshal(data, &st.s); err != nil {
			return nil, fmt.Errorf("plik danych %s jest uszkodzony: %w", path, err)
		}
		if st.s.NextID < 1 {
			st.s.NextID = 1
		}
	case errors.Is(err, os.ErrNotExist):
		// nowa, pusta baza
	default:
		return nil, fmt.Errorf("nie można odczytać %s: %w", path, err)
	}
	return st, nil
}

// mutate wykonuje zmianę pod blokadą. Gdy zmiana albo zapis się nie uda,
// stan w pamięci wraca do poprzedniego, żeby nie rozjechał się z plikiem.
func (st *Store) mutate(fn func(s *state) error) error {
	st.mu.Lock()
	defer st.mu.Unlock()

	snapshot, err := json.Marshal(st.s)
	if err != nil {
		return err
	}
	rollback := func() { _ = json.Unmarshal(snapshot, &st.s) }

	if err := fn(&st.s); err != nil {
		rollback()
		return err
	}
	if err := st.saveLocked(); err != nil {
		rollback()
		return fmt.Errorf("zapis danych: %w", err)
	}
	return nil
}

func (st *Store) saveLocked() error {
	if st.path == "" {
		return nil
	}
	data, err := json.MarshalIndent(st.s, "", "  ")
	if err != nil {
		return err
	}
	dir := filepath.Dir(st.path)
	tmp, err := os.CreateTemp(dir, ".data-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		return err
	}
	if err := os.Rename(tmpName, st.path); err != nil {
		os.Remove(tmpName)
		return err
	}
	return nil
}

// ---------------------------------------------------------------- Przeszkody

// AddObstacle dodaje przeszkodę. Gdy podano ClientID, który już istnieje w tym odcinku,
// zwraca istniejący wpis (created=false), więc ponowna wysyłka po utracie zasięgu nie dubluje.
func (st *Store) AddObstacle(o Obstacle) (out Obstacle, created bool, err error) {
	err = st.mutate(func(s *state) error {
		if o.ClientID != "" {
			for _, e := range s.Obstacles {
				if e.ClientID == o.ClientID && e.SectionKey == o.SectionKey {
					out = e
					return nil
				}
			}
		}
		now := st.now()
		o.ID = st.nextIDIn(s)
		o.ReportedAt = now
		o.LastVerifiedAt = now
		o.Confirmations = 1
		o.RemovalVotes = 0
		s.Obstacles = append(s.Obstacles, o)
		out = o
		created = true
		return nil
	})
	return
}

func (st *Store) nextIDIn(s *state) int64 {
	id := s.NextID
	s.NextID++
	return id
}

func (st *Store) ListObstacles(sectionKey string, includeInactive bool) []Obstacle {
	st.mu.RLock()
	defer st.mu.RUnlock()
	out := []Obstacle{}
	for _, o := range st.s.Obstacles {
		if o.SectionKey == sectionKey && (includeInactive || o.IsActive()) {
			out = append(out, o)
		}
	}
	return out
}

// VoteObstacle zwiększa licznik potwierdzeń (confirm=true) albo zgłoszeń usunięcia.
func (st *Store) VoteObstacle(id int64, confirm bool) (Obstacle, error) {
	var out Obstacle
	err := st.mutate(func(s *state) error {
		for i := range s.Obstacles {
			if s.Obstacles[i].ID != id {
				continue
			}
			if confirm {
				s.Obstacles[i].Confirmations++
			} else {
				s.Obstacles[i].RemovalVotes++
			}
			s.Obstacles[i].LastVerifiedAt = st.now()
			out = s.Obstacles[i]
			return nil
		}
		return errNotFound
	})
	return out, err
}

// ---------------------------------------------------------------- Spływy

func (st *Store) AddTrip(t Trip) (Trip, error) {
	var out Trip
	err := st.mutate(func(s *state) error {
		t.ID = st.nextIDIn(s)
		t.CreatedAt = st.now()
		s.Trips = append(s.Trips, t)
		out = t
		return nil
	})
	return out, err
}

func (st *Store) ListTrips() []Trip {
	st.mu.RLock()
	defer st.mu.RUnlock()
	out := make([]Trip, len(st.s.Trips))
	copy(out, st.s.Trips)
	return out
}

func (st *Store) GetTrip(id int64) (TripDetail, error) {
	st.mu.RLock()
	defer st.mu.RUnlock()
	return st.tripDetailLocked(id)
}

func (st *Store) tripDetailLocked(id int64) (TripDetail, error) {
	d := TripDetail{Participants: []Participant{}, Gear: []GearItem{}, CheckIns: []CheckIn{}}
	found := false
	for _, t := range st.s.Trips {
		if t.ID == id {
			d.Trip = t
			found = true
			break
		}
	}
	if !found {
		return TripDetail{}, errNotFound
	}
	for _, p := range st.s.Participants {
		if p.TripID == id {
			d.Participants = append(d.Participants, p)
		}
	}
	for _, g := range st.s.Gear {
		if g.TripID == id {
			d.Gear = append(d.Gear, g)
		}
	}
	for _, c := range st.s.CheckIns {
		if c.TripID == id {
			d.CheckIns = append(d.CheckIns, c)
		}
	}
	return d, nil
}

func tripExists(s *state, id int64) bool {
	for _, t := range s.Trips {
		if t.ID == id {
			return true
		}
	}
	return false
}

// DeleteTrip usuwa spływ razem z uczestnikami, wyposażeniem i zameldowaniami.
func (st *Store) DeleteTrip(id int64) error {
	return st.mutate(func(s *state) error {
		if !tripExists(s, id) {
			return errNotFound
		}
		s.Trips = filterTrips(s.Trips, func(t Trip) bool { return t.ID != id })
		s.Participants = filterSlice(s.Participants, func(p Participant) bool { return p.TripID != id })
		s.Gear = filterSlice(s.Gear, func(g GearItem) bool { return g.TripID != id })
		s.CheckIns = filterSlice(s.CheckIns, func(c CheckIn) bool { return c.TripID != id })
		return nil
	})
}

func filterTrips(in []Trip, keep func(Trip) bool) []Trip { return filterSlice(in, keep) }

func filterSlice[T any](in []T, keep func(T) bool) []T {
	out := make([]T, 0, len(in))
	for _, v := range in {
		if keep(v) {
			out = append(out, v)
		}
	}
	return out
}

func (st *Store) AddParticipant(tripID int64, p Participant) (Participant, error) {
	var out Participant
	err := st.mutate(func(s *state) error {
		if !tripExists(s, tripID) {
			return errNotFound
		}
		p.ID = st.nextIDIn(s)
		p.TripID = tripID
		s.Participants = append(s.Participants, p)
		out = p
		return nil
	})
	return out, err
}

func (st *Store) DeleteParticipant(tripID, id int64) error {
	return st.mutate(func(s *state) error {
		for i, p := range s.Participants {
			if p.ID == id && p.TripID == tripID {
				s.Participants = append(s.Participants[:i], s.Participants[i+1:]...)
				return nil
			}
		}
		return errNotFound
	})
}

func (st *Store) AddGear(tripID int64, g GearItem) (GearItem, error) {
	var out GearItem
	err := st.mutate(func(s *state) error {
		if !tripExists(s, tripID) {
			return errNotFound
		}
		g.ID = st.nextIDIn(s)
		g.TripID = tripID
		s.Gear = append(s.Gear, g)
		out = g
		return nil
	})
	return out, err
}

// GearPatch opisuje częściową zmianę pozycji wyposażenia. Pole nil oznacza „bez zmian”.
// AssignedToSet=true pozwala też wyczyścić przypisanie (AssignedTo=nil).
type GearPatch struct {
	AssignedToSet bool
	AssignedTo    *string
	Packed        *bool
}

func (st *Store) UpdateGear(tripID, id int64, patch GearPatch) (GearItem, error) {
	var out GearItem
	err := st.mutate(func(s *state) error {
		for i := range s.Gear {
			if s.Gear[i].ID != id || s.Gear[i].TripID != tripID {
				continue
			}
			if patch.AssignedToSet {
				s.Gear[i].AssignedTo = patch.AssignedTo
			}
			if patch.Packed != nil {
				s.Gear[i].Packed = *patch.Packed
			}
			out = s.Gear[i]
			return nil
		}
		return errNotFound
	})
	return out, err
}

func (st *Store) DeleteGear(tripID, id int64) error {
	return st.mutate(func(s *state) error {
		for i, g := range s.Gear {
			if g.ID == id && g.TripID == tripID {
				s.Gear = append(s.Gear[:i], s.Gear[i+1:]...)
				return nil
			}
		}
		return errNotFound
	})
}

// AddCheckIn zapisuje zameldowanie. ClientID działa jak przy przeszkodach (brak duplikatów).
func (st *Store) AddCheckIn(tripID int64, c CheckIn) (out CheckIn, created bool, err error) {
	err = st.mutate(func(s *state) error {
		if !tripExists(s, tripID) {
			return errNotFound
		}
		if c.ClientID != "" {
			for _, e := range s.CheckIns {
				if e.ClientID == c.ClientID && e.TripID == tripID {
					out = e
					return nil
				}
			}
		}
		c.ID = st.nextIDIn(s)
		c.TripID = tripID
		c.CreatedAt = st.now()
		s.CheckIns = append(s.CheckIns, c)
		out = c
		created = true
		return nil
	})
	return
}

func (st *Store) ListCheckIns(tripID int64) ([]CheckIn, error) {
	st.mu.RLock()
	defer st.mu.RUnlock()
	if !tripExists(&st.s, tripID) {
		return nil, errNotFound
	}
	out := []CheckIn{}
	for i := len(st.s.CheckIns) - 1; i >= 0; i-- { // od najnowszego
		if st.s.CheckIns[i].TripID == tripID {
			out = append(out, st.s.CheckIns[i])
		}
	}
	return out, nil
}
