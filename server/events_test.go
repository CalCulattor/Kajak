package main

import (
	"bufio"
	"net/http"
	"strings"
	"testing"
	"time"
)

type sseStream struct {
	lines chan string
	close func()
}

// openStream otwiera /api/events i przekazuje przychodzące linie na kanał.
func openStream(t *testing.T, baseURL, token string) *sseStream {
	t.Helper()
	req, err := http.NewRequest("GET", baseURL+"/api/events", nil)
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		resp.Body.Close()
		t.Fatalf("strumień: status %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("Content-Type = %q", ct)
	}
	s := &sseStream{lines: make(chan string, 100), close: func() { resp.Body.Close() }}
	t.Cleanup(s.close)
	go func() {
		sc := bufio.NewScanner(resp.Body)
		for sc.Scan() {
			s.lines <- sc.Text()
		}
		close(s.lines)
	}()
	// Pierwsza linia to komentarz „połączono” – po niej subskrypcja jest już aktywna.
	s.expectLine(t, ": połączono")
	return s
}

func (s *sseStream) expectLine(t *testing.T, want string) {
	t.Helper()
	select {
	case line, ok := <-s.lines:
		if !ok || line != want {
			t.Fatalf("oczekiwano %q, jest %q (otwarty=%v)", want, line, ok)
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("brak linii %q", want)
	}
}

// expectEvent czeka na zdarzenie o danej nazwie i zawartości danych.
func (s *sseStream) expectEvent(t *testing.T, name, dataContains string) {
	t.Helper()
	deadline := time.After(3 * time.Second)
	for {
		select {
		case line, ok := <-s.lines:
			if !ok {
				t.Fatalf("strumień zamknięty, a oczekiwano zdarzenia %s", name)
			}
			if line == "event: "+name {
				select {
				case data := <-s.lines:
					if strings.Contains(data, dataContains) {
						return
					}
				case <-deadline:
					t.Fatalf("brak danych zdarzenia %s", name)
				}
			}
		case <-deadline:
			t.Fatalf("brak zdarzenia %s z %q", name, dataContains)
		}
	}
}

func TestEventsRequireLogin(t *testing.T) {
	ts, _ := newTestServer(t, "")
	if code, _ := callAs(t, "", "GET", ts.URL+"/api/events", nil); code != 401 {
		t.Fatalf("bez logowania: %d", code)
	}
}

func TestLiveTripEvents(t *testing.T) {
	ts, _ := newTestServer(t, "")
	owner := authToken(t, ts.URL, "owner")
	ola := authToken(t, ts.URL, "Ola")

	ownerStream := openStream(t, ts.URL, owner)

	code, body := callAs(t, owner, "POST", ts.URL+"/api/trips", map[string]any{"title": "Spływ", "start_date": "2026-10-10"})
	if code != 201 {
		t.Fatalf("create: %d %s", code, body)
	}
	var trip Trip
	decodeInto(t, body, &trip)
	url := ts.URL + "/api/trips/" + itoa(trip.ID)
	ownerStream.expectEvent(t, "trips", "")

	// Dołączenie Oli ma od razu dotrzeć do organizatora.
	if code, _ = callAs(t, ola, "POST", url+"/participants", map[string]any{"car_seats": 2}); code != 201 {
		t.Fatalf("join: %d", code)
	}
	ownerStream.expectEvent(t, "trip", `"trip_id":`+itoa(trip.ID))

	// Zmiana wyposażenia, zameldowanie i opuszczenie spływu też są ogłaszane.
	code, body = callAs(t, owner, "POST", url+"/gear", map[string]any{"name": "Namiot"})
	if code != 201 {
		t.Fatalf("gear: %d", code)
	}
	ownerStream.expectEvent(t, "trip", `"trip_id":`+itoa(trip.ID))
	if code, _ = callAs(t, ola, "POST", url+"/checkins", map[string]any{"lat": 50.0, "lon": 20.0}); code != 201 {
		t.Fatalf("checkin: %d", code)
	}
	ownerStream.expectEvent(t, "trip", `"trip_id":`+itoa(trip.ID))

	_, body = callAs(t, owner, "GET", url, nil)
	var detail TripDetail
	decodeInto(t, body, &detail)
	for _, p := range detail.Participants {
		if p.Name == "Ola" {
			if code, _ = callAs(t, ola, "DELETE", url+"/participants/"+itoa(p.ID), nil); code != 204 {
				t.Fatalf("leave: %d", code)
			}
		}
	}
	ownerStream.expectEvent(t, "trip", `"trip_id":`+itoa(trip.ID))

	// Usunięcie spływu trafia też do osób, które już w nim nie są (żeby usunęły kopię).
	olaStream := openStream(t, ts.URL, ola)
	if code, _ = callAs(t, owner, "DELETE", url, nil); code != 204 {
		t.Fatalf("delete: %d", code)
	}
	olaStream.expectEvent(t, "trip", `"trip_id":`+itoa(trip.ID))
}

func TestLiveRouteAndObstacleEvents(t *testing.T) {
	ts, _ := newTestServer(t, "")
	stream := openStream(t, ts.URL, authToken(t, ts.URL, "obserwator"))

	if code, _ := call(t, "POST", ts.URL+"/api/routes", validRoute()); code != 201 {
		t.Fatalf("route: %d", code)
	}
	stream.expectEvent(t, "routes", "")

	code, body := call(t, "POST", ts.URL+"/api/sections/krutynia/obstacles", map[string]any{"type": "WEIR"})
	if code != 201 {
		t.Fatalf("obstacle: %d", code)
	}
	stream.expectEvent(t, "obstacles", `"section_key":"krutynia"`)

	var o ObstacleView
	decodeInto(t, body, &o)
	if code, _ = call(t, "POST", ts.URL+"/api/obstacles/"+itoa(o.ID)+"/confirm", nil); code != 200 {
		t.Fatalf("confirm: %d", code)
	}
	stream.expectEvent(t, "obstacles", `"section_key":"krutynia"`)
}

func TestHubDropsForSlowSubscriberAndCloses(t *testing.T) {
	h := newHub()
	ch, cancel, ok := h.Subscribe()
	if !ok {
		t.Fatal("subskrypcja")
	}
	for i := 0; i < subscriberQueue+10; i++ {
		h.Publish("x", "{}") // nie może się zablokować mimo pełnej kolejki
	}
	if len(ch) != subscriberQueue {
		t.Fatalf("kolejka: %d", len(ch))
	}
	cancel()
	h.Publish("x", "{}") // po odsubskrybowaniu nic się nie dzieje

	h.CloseAll()
	if _, _, ok := h.Subscribe(); ok {
		t.Fatal("zamknięty hub nie powinien przyjmować subskrypcji")
	}
	select {
	case <-h.done:
	default:
		t.Fatal("done powinno być zamknięte")
	}
}
