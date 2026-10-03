package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

const testPassword = "haslo-testowe-1"

// defaultTokens: adres serwera testowego -> token domyślnego użytkownika „tester”,
// który jest dopisywany do żądań wysyłanych przez call.
var defaultTokens sync.Map

func TestMain(m *testing.M) {
	pbkdf2Iterations = 1000 // testy nie muszą płacić pełnego kosztu hasha
	os.Exit(m.Run())
}

func newTestServer(t *testing.T, dataFile string) (*httptest.Server, *Store) {
	t.Helper()
	st, err := OpenStore(dataFile)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(NewServer(st).Handler())
	t.Cleanup(func() {
		ts.Close()
		defaultTokens.Delete(ts.URL)
	})
	defaultTokens.Store(ts.URL, authToken(t, ts.URL, "tester"))
	return ts, st
}

// authToken loguje użytkownika (a gdy konta nie ma – rejestruje je) i zwraca token.
func authToken(t *testing.T, baseURL, username string) string {
	t.Helper()
	creds := map[string]any{"username": username, "password": testPassword}
	code, body := callAs(t, "", "POST", baseURL+"/api/login", creds)
	if code == http.StatusUnauthorized {
		code, body = callAs(t, "", "POST", baseURL+"/api/register", creds)
	}
	if code != 200 && code != 201 {
		t.Fatalf("logowanie/rejestracja %s: %d %s", username, code, body)
	}
	var resp authResponse
	decodeInto(t, body, &resp)
	return resp.Token
}

// call wysyła żądanie jako domyślny użytkownik „tester”.
func call(t *testing.T, method, url string, body any) (int, []byte) {
	t.Helper()
	token := ""
	defaultTokens.Range(func(k, v any) bool {
		if strings.HasPrefix(url, k.(string)) {
			token = v.(string)
			return false
		}
		return true
	})
	return callAs(t, token, method, url, body)
}

// callAs wysyła żądanie z podanym tokenem (pusty = bez logowania).
func callAs(t *testing.T, token, method, url string, body any) (int, []byte) {
	t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	default:
		raw, err := json.Marshal(b)
		if err != nil {
			t.Fatal(err)
		}
		rd = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, url, rd)
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
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, data
}

func decodeInto(t *testing.T, data []byte, v any) {
	t.Helper()
	if err := json.Unmarshal(data, v); err != nil {
		t.Fatalf("niepoprawny JSON %q: %v", data, err)
	}
}

func TestHealth(t *testing.T) {
	ts, _ := newTestServer(t, "")
	code, body := call(t, "GET", ts.URL+"/api/health", nil)
	if code != 200 || !strings.Contains(string(body), "ok") {
		t.Fatalf("health: %d %s", code, body)
	}
}

func TestObstacleLifecycleAndRules(t *testing.T) {
	ts, _ := newTestServer(t, "")
	base := ts.URL + "/api/sections/dunajec-przelom/obstacles"

	code, body := call(t, "POST", base, map[string]any{
		"type": "STRAINER", "description": "drzewo przy lewym brzegu", "lat": 49.4, "lon": 20.4,
	})
	if code != 201 {
		t.Fatalf("create: %d %s", code, body)
	}
	var o ObstacleView
	decodeInto(t, body, &o)
	if !o.Active || o.Confirmations != 1 || o.ID < 1 {
		t.Fatalf("niepoprawna przeszkoda: %+v", o)
	}

	// Jedno zgłoszenie usunięcia nie wystarcza.
	voteURL := ts.URL + "/api/obstacles/" + itoa(o.ID) + "/remove-vote"
	code, body = call(t, "POST", voteURL, nil)
	decodeInto(t, body, &o)
	if code != 200 || !o.Active {
		t.Fatalf("po 1 głosie powinna być aktywna: %d %+v", code, o)
	}
	// Drugie zgłoszenie (2 > 1 potwierdzenie) ją usuwa z listy.
	_, body = call(t, "POST", voteURL, nil)
	decodeInto(t, body, &o)
	if o.Active {
		t.Fatalf("po 2 głosach nie powinna być aktywna: %+v", o)
	}

	var list []ObstacleView
	_, body = call(t, "GET", base, nil)
	decodeInto(t, body, &list)
	if len(list) != 0 {
		t.Fatalf("lista aktywnych powinna być pusta: %+v", list)
	}
	_, body = call(t, "GET", base+"?include_inactive=true", nil)
	decodeInto(t, body, &list)
	if len(list) != 1 || list[0].Active {
		t.Fatalf("include_inactive: %+v", list)
	}

	// Potwierdzenia przywracają przeszkodę (3 potwierdzenia >= 2 głosy usunięcia).
	confirmURL := ts.URL + "/api/obstacles/" + itoa(o.ID) + "/confirm"
	call(t, "POST", confirmURL, nil)
	_, body = call(t, "POST", confirmURL, nil)
	decodeInto(t, body, &o)
	if !o.Active || o.Confirmations != 3 {
		t.Fatalf("po potwierdzeniach powinna być aktywna: %+v", o)
	}
}

func TestObstacleValidation(t *testing.T) {
	ts, _ := newTestServer(t, "")
	base := ts.URL + "/api/sections/ok-key/obstacles"

	cases := []struct {
		name string
		url  string
		body any
		want int
	}{
		{"zły typ", base, map[string]any{"type": "UFO"}, 400},
		{"lat bez lon", base, map[string]any{"type": "WEIR", "lat": 10.0}, 400},
		{"współrzędne poza zakresem", base, map[string]any{"type": "WEIR", "lat": 91.0, "lon": 0.0}, 400},
		{"nieznane pole", base, map[string]any{"type": "WEIR", "foo": 1}, 400},
		{"zepsuty JSON", base, "{nie json", 400},
		{"dane po obiekcie", base, `{"type":"WEIR"} {"type":"WEIR"}`, 400},
		{"zły klucz odcinka", ts.URL + "/api/sections/ZLY_KLUCZ/obstacles", map[string]any{"type": "WEIR"}, 400},
		{"poprawne bez pozycji", base, map[string]any{"type": "PORTAGE", "description": "przenoska"}, 201},
	}
	for _, c := range cases {
		code, body := call(t, "POST", c.url, c.body)
		if code != c.want {
			t.Errorf("%s: kod %d, oczekiwano %d (%s)", c.name, code, c.want, body)
		}
	}

	code, _ := call(t, "POST", ts.URL+"/api/obstacles/9999/confirm", nil)
	if code != 404 {
		t.Errorf("nieistniejąca przeszkoda: kod %d", code)
	}
	code, _ = call(t, "POST", ts.URL+"/api/obstacles/abc/confirm", nil)
	if code != 400 {
		t.Errorf("zły identyfikator: kod %d", code)
	}
}

func TestClientIDDeduplicates(t *testing.T) {
	ts, _ := newTestServer(t, "")
	base := ts.URL + "/api/sections/krutynia/obstacles"
	payload := map[string]any{"client_id": "tel-1-abc", "type": "STRAINER"}

	code, body := call(t, "POST", base, payload)
	var first ObstacleView
	decodeInto(t, body, &first)
	if code != 201 {
		t.Fatalf("pierwsze wysłanie: %d", code)
	}
	code, body = call(t, "POST", base, payload)
	var second ObstacleView
	decodeInto(t, body, &second)
	if code != 200 || second.ID != first.ID {
		t.Fatalf("ponowne wysłanie powinno zwrócić ten sam wpis: %d %+v", code, second)
	}

	var list []ObstacleView
	_, body = call(t, "GET", base, nil)
	decodeInto(t, body, &list)
	if len(list) != 1 {
		t.Fatalf("oczekiwano jednego wpisu, jest %d", len(list))
	}
}

func TestTripFlow(t *testing.T) {
	ts, _ := newTestServer(t, "")

	code, body := call(t, "POST", ts.URL+"/api/trips", map[string]any{
		"title": "Weekend na Krutyni", "section_key": "krutynia",
		"start_date": futureDate(), "overnight": true,
	})
	if code != 201 {
		t.Fatalf("create trip: %d %s", code, body)
	}
	var trip Trip
	decodeInto(t, body, &trip)
	tripURL := ts.URL + "/api/trips/" + itoa(trip.ID)

	// Ola dołącza sama jako zalogowana użytkowniczka.
	olaToken := authToken(t, ts.URL, "Ola")
	code, body = callAs(t, olaToken, "POST", tripURL+"/participants", map[string]any{"car_seats": 4, "needs_kayak": true})
	if code != 201 {
		t.Fatalf("participant: %d %s", code, body)
	}
	code, body = call(t, "POST", tripURL+"/gear", map[string]any{"name": "Namiot"})
	if code != 201 {
		t.Fatalf("gear: %d %s", code, body)
	}
	var gear GearItem
	decodeInto(t, body, &gear)
	gearURL := tripURL + "/gear/" + itoa(gear.ID)

	code, body = call(t, "PATCH", gearURL, map[string]any{"assigned_to": "Ola", "packed": true})
	decodeInto(t, body, &gear)
	if code != 200 || gear.AssignedTo == nil || *gear.AssignedTo != "Ola" || !gear.Packed {
		t.Fatalf("patch gear: %d %+v", code, gear)
	}
	// Jawny null czyści przypisanie, a pole packed zostaje bez zmian.
	code, body = call(t, "PATCH", gearURL, `{"assigned_to": null}`)
	decodeInto(t, body, &gear)
	if code != 200 || gear.AssignedTo != nil || !gear.Packed {
		t.Fatalf("czyszczenie przypisania: %d %+v", code, gear)
	}
	if code, _ = call(t, "PATCH", gearURL, `{}`); code != 400 {
		t.Errorf("pusty patch powinien dać 400, jest %d", code)
	}

	code, body = callAs(t, olaToken, "POST", tripURL+"/checkins", map[string]any{
		"client_id": "c1", "person_name": "Ola", "lat": 53.77, "lon": 21.5, "needs_help": true,
		"fix_at": "2026-10-10T09:00:00Z",
	})
	if code != 201 {
		t.Fatalf("checkin: %d %s", code, body)
	}

	var ci CheckIn
	// (id zameldowania pobieramy ze szczegółów poniżej)
	var detail TripDetail
	_, body = call(t, "GET", tripURL, nil)
	decodeInto(t, body, &detail)
	// Organizator + Ola.
	if len(detail.Participants) != 2 || len(detail.Gear) != 1 || len(detail.CheckIns) != 1 {
		t.Fatalf("szczegóły spływu: %+v", detail)
	}
	if !detail.CheckIns[0].NeedsHelp || detail.CheckIns[0].FixAt.Hour() != 9 {
		t.Fatalf("zameldowanie: %+v", detail.CheckIns[0])
	}

	// Wezwanie pomocy odwołać może tylko jego autor.
	ciURL := tripURL + "/checkins/" + itoa(detail.CheckIns[0].ID)
	if code, _ = call(t, "PATCH", ciURL, map[string]any{"needs_help": false}); code != 403 {
		t.Errorf("cudze wezwanie: oczekiwano 403, jest %d", code)
	}
	if code, _ = callAs(t, olaToken, "PATCH", ciURL, map[string]any{}); code != 400 {
		t.Errorf("brak needs_help: oczekiwano 400, jest %d", code)
	}
	code, body = callAs(t, olaToken, "PATCH", ciURL, map[string]any{"needs_help": false})
	decodeInto(t, body, &ci)
	if code != 200 || ci.NeedsHelp {
		t.Fatalf("odwołanie pomocy: %d %+v", code, ci)
	}
	if code, _ = callAs(t, olaToken, "PATCH", tripURL+"/checkins/99999", map[string]any{"needs_help": false}); code != 404 {
		t.Errorf("nieistniejące zameldowanie: oczekiwano 404, jest %d", code)
	}

	// Usunięcie spływu usuwa wszystko, co do niego należało.
	if code, _ = call(t, "DELETE", tripURL, nil); code != 204 {
		t.Fatalf("delete trip: %d", code)
	}
	if code, _ = call(t, "GET", tripURL, nil); code != 404 {
		t.Fatalf("po usunięciu spodziewano się 404, jest %d", code)
	}
	if code, _ = call(t, "GET", tripURL+"/checkins", nil); code != 404 {
		t.Fatalf("zameldowania usuniętego spływu: %d", code)
	}
}

func TestTripAndCheckInValidation(t *testing.T) {
	ts, _ := newTestServer(t, "")
	code, _ := call(t, "POST", ts.URL+"/api/trips", map[string]any{
		"title": "T", "start_date": "2026-02-30",
	})
	if code != 400 {
		t.Errorf("nieistniejąca data: %d", code)
	}
	code, _ = call(t, "POST", ts.URL+"/api/trips", map[string]any{
		"title": " ", "start_date": futureDate(),
	})
	if code != 400 {
		t.Errorf("pusty tytuł: %d", code)
	}

	code, _ = call(t, "POST", ts.URL+"/api/trips", map[string]any{
		"title": "T", "start_date": futureDate(), "start_time": "25:00",
	})
	if code != 400 {
		t.Errorf("zła godzina: %d", code)
	}
	code, body0 := call(t, "POST", ts.URL+"/api/trips", map[string]any{
		"title": "Z godziną", "start_date": futureDate(), "start_time": "09:30",
	})
	if code != 201 && code != 200 {
		t.Fatalf("spływ z godziną: %d", code)
	}
	var timed Trip
	decodeInto(t, body0, &timed)
	if timed.StartTime != "09:30" {
		t.Errorf("start_time = %q", timed.StartTime)
	}

	_, body := call(t, "POST", ts.URL+"/api/trips", map[string]any{
		"title": "T", "start_date": futureDate(),
	})
	var trip Trip
	decodeInto(t, body, &trip)
	url := ts.URL + "/api/trips/" + itoa(trip.ID)

	code, _ = call(t, "POST", url+"/checkins", map[string]any{"person_name": "tester", "lat": 10.0})
	if code != 400 {
		t.Errorf("brak lon: %d", code)
	}
	code, _ = call(t, "POST", url+"/participants", map[string]any{"car_seats": -1})
	if code != 400 {
		t.Errorf("ujemne miejsca: %d", code)
	}
	code, _ = call(t, "POST", ts.URL+"/api/trips/999/participants", map[string]any{"car_seats": 1})
	if code != 404 {
		t.Errorf("nieistniejący spływ: %d", code)
	}
}

func TestPersistenceAcrossRestart(t *testing.T) {
	file := filepath.Join(t.TempDir(), "data.json")

	ts1, _ := newTestServer(t, file)
	code, body := call(t, "POST", ts1.URL+"/api/sections/krutynia/obstacles", map[string]any{"type": "WEIR"})
	if code != 201 {
		t.Fatalf("create: %d %s", code, body)
	}
	var first ObstacleView
	decodeInto(t, body, &first)
	ts1.Close()

	ts2, _ := newTestServer(t, file)
	var list []ObstacleView
	_, body = call(t, "GET", ts2.URL+"/api/sections/krutynia/obstacles", nil)
	decodeInto(t, body, &list)
	if len(list) != 1 || list[0].ID != first.ID {
		t.Fatalf("dane nie przetrwały restartu: %+v", list)
	}
	// Nowy wpis dostaje świeży identyfikator, bez kolizji ze starym.
	_, body = call(t, "POST", ts2.URL+"/api/sections/krutynia/obstacles", map[string]any{"type": "PORTAGE"})
	var second ObstacleView
	decodeInto(t, body, &second)
	if second.ID == first.ID {
		t.Fatalf("kolizja identyfikatorów: %d", second.ID)
	}
}

func TestCorruptedDataFileIsRejected(t *testing.T) {
	file := filepath.Join(t.TempDir(), "data.json")
	if err := writeFile(file, "{to nie jest json"); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenStore(file); err == nil {
		t.Fatal("uszkodzony plik powinien dać błąd, żeby nie nadpisać danych")
	}
}

func TestRequestBodyTooLarge(t *testing.T) {
	ts, _ := newTestServer(t, "")
	big := `{"type":"WEIR","description":"` + strings.Repeat("a", maxBodyBytes+10) + `"}`
	code, _ := call(t, "POST", ts.URL+"/api/sections/krutynia/obstacles", big)
	if code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oczekiwano 413, jest %d", code)
	}
}

// futureDate zwraca datę za 30 dni (testy nie mogą zależeć od stałego kalendarza).
func futureDate() string { return time.Now().UTC().AddDate(0, 0, 30).Format("2006-01-02") }

func TestLiveLocationsAndHelp(t *testing.T) {
	ts, _ := newTestServer(t, "")
	_, body := call(t, "POST", ts.URL+"/api/trips", map[string]any{
		"title": "Dunajec", "section_key": "dunajec", "start_date": futureDate(), "overnight": false,
	})
	var trip Trip
	decodeInto(t, body, &trip)
	tripURL := ts.URL + "/api/trips/" + itoa(trip.ID)

	ola := authToken(t, ts.URL, "Ola")
	if code, _ := callAs(t, ola, "POST", tripURL+"/participants", map[string]any{"car_seats": 0, "needs_kayak": false}); code != 201 {
		t.Fatalf("Ola nie dołączyła: %d", code)
	}
	outsider := authToken(t, ts.URL, "Obcy")

	// Pozycje zapisują i czytają tylko uczestnicy.
	put := map[string]any{"lat": 49.4, "lon": 20.4}
	if code, _ := callAs(t, outsider, "PUT", tripURL+"/location", put); code != 403 {
		t.Errorf("obcy zapis: oczekiwano 403, jest %d", code)
	}
	if code, _ := callAs(t, outsider, "GET", tripURL+"/locations", nil); code != 403 {
		t.Errorf("obcy odczyt: oczekiwano 403, jest %d", code)
	}
	if code, _ := callAs(t, ola, "PUT", tripURL+"/location", map[string]any{"lat": 99.0, "lon": 20.4}); code != 400 {
		t.Errorf("zła szerokość: oczekiwano 400, jest %d", code)
	}
	if code, _ := callAs(t, ola, "PUT", tripURL+"/location", put); code != 204 {
		t.Fatalf("zapis pozycji: %d", code)
	}

	var list []LocationView
	_, body = call(t, "GET", tripURL+"/locations", nil)
	decodeInto(t, body, &list)
	if len(list) != 1 || list[0].Username != "Ola" || list[0].NeedsHelp {
		t.Fatalf("lista pozycji: %+v", list)
	}

	// Wezwanie pomocy (zameldowanie z needs_help) oznacza osobę na liście.
	code, body := callAs(t, ola, "POST", tripURL+"/checkins", map[string]any{
		"client_id": "h1", "lat": 49.41, "lon": 20.41, "needs_help": true,
	})
	if code != 201 {
		t.Fatalf("wezwanie pomocy: %d %s", code, body)
	}
	var ci CheckIn
	decodeInto(t, body, &ci)
	_, body = call(t, "GET", tripURL+"/locations", nil)
	decodeInto(t, body, &list)
	if len(list) != 1 || !list[0].NeedsHelp || list[0].HelpCheckInID != ci.ID {
		t.Fatalf("pomoc na liście: %+v", list)
	}

	// Odwołanie pomocy zdejmuje oznaczenie.
	if code, _ = callAs(t, ola, "PATCH", tripURL+"/checkins/"+itoa(ci.ID), map[string]any{"needs_help": false}); code != 200 {
		t.Fatalf("odwołanie: %d", code)
	}
	_, body = call(t, "GET", tripURL+"/locations", nil)
	decodeInto(t, body, &list)
	if len(list) != 1 || list[0].NeedsHelp {
		t.Fatalf("po odwołaniu: %+v", list)
	}
}
