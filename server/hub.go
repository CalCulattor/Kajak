package main

import (
	"fmt"
	"net/http"
	"sync"
	"time"
)

// Zmiany na żywo: klienci otwierają jeden strumień Server-Sent Events (GET /api/events),
// a serwer wysyła krótkie powiadomienia typu „spływ 5 się zmienił”. Powiadomienie nie niesie
// danych – klient pobiera je zwykłym, chronionym uprawnieniami API. Dzięki temu strumień
// niczego nie ujawnia ponad identyfikatory i nie omija kontroli dostępu.

const (
	maxSubscribers  = 500
	subscriberQueue = 64
	pingEvery       = 20 * time.Second
	writeTimeout    = 10 * time.Second
)

type Event struct {
	Name string
	Data string
}

type hub struct {
	mu     sync.Mutex
	subs   map[chan Event]struct{}
	done   chan struct{}
	closed bool
}

func newHub() *hub {
	return &hub{subs: map[chan Event]struct{}{}, done: make(chan struct{})}
}

// Subscribe zwraca kanał zdarzeń i funkcję odsubskrybowania; false, gdy hub jest pełny lub zamknięty.
func (h *hub) Subscribe() (<-chan Event, func(), bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed || len(h.subs) >= maxSubscribers {
		return nil, nil, false
	}
	ch := make(chan Event, subscriberQueue)
	h.subs[ch] = struct{}{}
	return ch, func() {
		h.mu.Lock()
		delete(h.subs, ch)
		h.mu.Unlock()
	}, true
}

// Publish wysyła zdarzenie do wszystkich subskrybentów; wolny klient traci zdarzenie
// (po ponownym połączeniu i tak robi pełną synchronizację).
func (h *hub) Publish(name, data string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs {
		select {
		case ch <- Event{Name: name, Data: data}:
		default:
		}
	}
}

// CloseAll kończy wszystkie strumienie (przy zamykaniu serwera).
func (h *hub) CloseAll() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.closed {
		h.closed = true
		close(h.done)
	}
}

func (srv *Server) tripChanged(id int64) {
	srv.hub.Publish("trip", fmt.Sprintf(`{"trip_id":%d}`, id))
}

func (srv *Server) tripsChanged() { srv.hub.Publish("trips", "{}") }

func (srv *Server) routesChanged() { srv.hub.Publish("routes", "{}") }

func (srv *Server) obstaclesChanged(sectionKey string) {
	// Klucz odcinka jest ograniczony wyrażeniem regularnym, więc nie wymaga zmiany znaczenia znaków.
	srv.hub.Publish("obstacles", fmt.Sprintf(`{"section_key":"%s"}`, sectionKey))
}

// events to strumień SSE dla zalogowanych użytkowników.
func (srv *Server) events(w http.ResponseWriter, r *http.Request) {
	token := bearerToken(r)
	if _, ok := srv.currentUser(w, r); !ok {
		return
	}
	events, cancel, ok := srv.hub.Subscribe()
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "zbyt wiele połączeń na żywo")
		return
	}
	defer cancel()

	rc := http.NewResponseController(w)
	// Serwer ma krótkie limity czasu na zwykłe żądania; strumień ma żyć długo.
	_ = rc.SetReadDeadline(time.Time{})
	send := func(format string, args ...any) bool {
		_ = rc.SetWriteDeadline(time.Now().Add(writeTimeout))
		if _, err := fmt.Fprintf(w, format, args...); err != nil {
			return false
		}
		return rc.Flush() == nil
	}

	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Accel-Buffering", "no") // reverse proxy (nginx) nie może buforować strumienia
	w.WriteHeader(http.StatusOK)
	if !send(": połączono\n\n") {
		return
	}

	ticker := time.NewTicker(pingEvery)
	defer ticker.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-srv.hub.done:
			return
		case ev := <-events:
			if !send("event: %s\ndata: %s\n\n", ev.Name, ev.Data) {
				return
			}
		case <-ticker.C:
			// Wylogowany token nie powinien dalej dostawać zdarzeń.
			if _, valid := srv.store.UsernameForToken(hashToken(token)); !valid {
				return
			}
			if !send(": ping\n\n") {
				return
			}
		}
	}
}
