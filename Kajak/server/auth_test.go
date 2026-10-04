package main

import (
	"encoding/hex"
	"net/http"
	"strings"
	"testing"
)

func TestPBKDF2KnownVectors(t *testing.T) {
	// Wektory z RFC 7914 (sekcja 11) oraz wyliczony niezależnie przez hashlib.pbkdf2_hmac.
	cases := []struct {
		password, salt string
		iter           int
		want           string
	}{
		{"passwd", "salt", 1, "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"},
		{"Password", "NaCl", 80000, "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"},
	}
	for _, c := range cases {
		got := hex.EncodeToString(pbkdf2SHA256([]byte(c.password), []byte(c.salt), c.iter, 64))
		if got != c.want {
			t.Errorf("pbkdf2(%q,%q,%d) = %s", c.password, c.salt, c.iter, got)
		}
	}
}

func TestRegisterLoginLogout(t *testing.T) {
	ts, _ := newTestServer(t, "")
	creds := map[string]any{"username": "Marcel", "password": testPassword}

	code, body := callAs(t, "", "POST", ts.URL+"/api/register", creds)
	var reg authResponse
	decodeInto(t, body, &reg)
	if code != 201 || reg.Token == "" || reg.Username != "Marcel" {
		t.Fatalf("rejestracja: %d %s", code, body)
	}

	// Nazwa musi być unikalna, także bez względu na wielkość liter.
	for _, name := range []string{"Marcel", "marcel", "MARCEL"} {
		code, _ = callAs(t, "", "POST", ts.URL+"/api/register", map[string]any{"username": name, "password": testPassword})
		if code != http.StatusConflict {
			t.Errorf("duplikat %q: %d", name, code)
		}
	}

	code, body = callAs(t, reg.Token, "GET", ts.URL+"/api/me", nil)
	if code != 200 || !strings.Contains(string(body), "Marcel") {
		t.Fatalf("me: %d %s", code, body)
	}

	// Logowanie bez względu na wielkość liter zwraca kanoniczną nazwę.
	code, body = callAs(t, "", "POST", ts.URL+"/api/login", map[string]any{"username": "marcel", "password": testPassword})
	var login authResponse
	decodeInto(t, body, &login)
	if code != 200 || login.Username != "Marcel" || login.Token == reg.Token {
		t.Fatalf("login: %d %s", code, body)
	}

	if code, _ = callAs(t, "", "POST", ts.URL+"/api/login", map[string]any{"username": "Marcel", "password": "zle-haslo-1"}); code != 401 {
		t.Errorf("złe hasło: %d", code)
	}
	if code, _ = callAs(t, "", "POST", ts.URL+"/api/login", map[string]any{"username": "nikt", "password": testPassword}); code != 401 {
		t.Errorf("nieznany użytkownik: %d", code)
	}

	// Wylogowanie unieważnia tylko ten token.
	if code, _ = callAs(t, reg.Token, "POST", ts.URL+"/api/logout", nil); code != 204 {
		t.Fatalf("logout: %d", code)
	}
	if code, _ = callAs(t, reg.Token, "GET", ts.URL+"/api/me", nil); code != 401 {
		t.Errorf("token po wylogowaniu: %d", code)
	}
	if code, _ = callAs(t, login.Token, "GET", ts.URL+"/api/me", nil); code != 200 {
		t.Errorf("drugi token powinien nadal działać: %d", code)
	}
}

func TestRegisterValidation(t *testing.T) {
	ts, _ := newTestServer(t, "")
	bad := []map[string]any{
		{"username": "ab", "password": testPassword},
		{"username": strings.Repeat("a", 25), "password": testPassword},
		{"username": "ma ła", "password": testPassword},
		{"username": "zażółć", "password": testPassword},
		{"username": "ok_user", "password": "krotkie"},
		{"username": "ok_user", "password": strings.Repeat("a", 129)},
		{"username": "ok_user", "password": testPassword, "email": "a@b.pl"},
	}
	for i, b := range bad {
		if code, body := callAs(t, "", "POST", ts.URL+"/api/register", b); code != 400 {
			t.Errorf("przypadek %d: %d %s", i, code, body)
		}
	}
}

func TestPasswordsAreNotStoredInPlain(t *testing.T) {
	ts, st := newTestServer(t, "")
	callAs(t, "", "POST", ts.URL+"/api/register", map[string]any{"username": "sekret", "password": "bardzo-tajne-haslo"})
	st.mu.RLock()
	defer st.mu.RUnlock()
	for _, u := range st.s.Users {
		if strings.Contains(u.Hash, "tajne") || u.Salt == "" || len(u.Hash) != 64 {
			t.Fatalf("niepoprawny zapis użytkownika: %+v", u)
		}
	}
	for _, s := range st.s.Sessions {
		if len(s.TokenHash) != 64 {
			t.Fatalf("token powinien być zapisany jako skrót: %+v", s)
		}
	}
}

func TestLoginThrottling(t *testing.T) {
	ts, _ := newTestServer(t, "")
	authToken(t, ts.URL, "ofiara")
	bad := map[string]any{"username": "ofiara", "password": "zle-haslo-1"}
	blockedAt := 0
	for i := 1; i <= 10; i++ {
		code, _ := callAs(t, "", "POST", ts.URL+"/api/login", bad)
		if code == http.StatusTooManyRequests {
			blockedAt = i
			break
		}
		if code != 401 {
			t.Fatalf("próba %d: %d", i, code)
		}
	}
	if blockedAt == 0 || blockedAt > 9 {
		t.Fatalf("blokada powinna zadziałać po ok. 8 błędach, zadziałała przy próbie %d", blockedAt)
	}
	// Blokada dotyczy też poprawnego hasła, dopóki okno nie minie.
	good := map[string]any{"username": "ofiara", "password": testPassword}
	if code, _ := callAs(t, "", "POST", ts.URL+"/api/login", good); code != http.StatusTooManyRequests {
		t.Fatalf("zablokowane konto: %d", code)
	}
	// Inne konta nie są dotknięte.
	if code, _ := callAs(t, "", "POST", ts.URL+"/api/login", map[string]any{"username": "tester", "password": testPassword}); code != 200 {
		t.Fatalf("inne konto: %d", code)
	}
}

func TestWritesRequireLogin(t *testing.T) {
	ts, _ := newTestServer(t, "")
	writes := []struct{ method, path string }{
		{"POST", "/api/routes"},
		{"POST", "/api/sections/krutynia/obstacles"},
		{"POST", "/api/obstacles/1/confirm"},
		{"POST", "/api/obstacles/1/remove-vote"},
		{"GET", "/api/trips"},
		{"POST", "/api/trips"},
		{"GET", "/api/trips/1"},
		{"DELETE", "/api/trips/1"},
		{"POST", "/api/trips/1/participants"},
		{"DELETE", "/api/trips/1/participants/1"},
		{"POST", "/api/trips/1/gear"},
		{"PATCH", "/api/trips/1/gear/1"},
		{"PUT", "/api/trips/1/gear/1/confirm"},
		{"DELETE", "/api/trips/1/gear/1"},
		{"GET", "/api/trips/1/checkins"},
		{"POST", "/api/trips/1/checkins"},
	}
	for _, w := range writes {
		if code, _ := callAs(t, "", w.method, ts.URL+w.path, "{}"); code != 401 {
			t.Errorf("%s %s bez logowania: %d", w.method, w.path, code)
		}
		if code, _ := callAs(t, "zly-token", w.method, ts.URL+w.path, "{}"); code != 401 {
			t.Errorf("%s %s ze złym tokenem: %d", w.method, w.path, code)
		}
	}
	// Odczyt tras i przeszkód oraz health pozostają publiczne.
	for _, path := range []string{"/api/health", "/api/routes", "/api/sections/krutynia/obstacles"} {
		if code, _ := callAs(t, "", "GET", ts.URL+path, nil); code != 200 {
			t.Errorf("GET %s: %d", path, code)
		}
	}
}

func TestTripPermissions(t *testing.T) {
	ts, _ := newTestServer(t, "")
	owner := authToken(t, ts.URL, "owner")
	ola := authToken(t, ts.URL, "Ola")
	ewa := authToken(t, ts.URL, "Ewa")

	// Twórca jest organizatorem niezależnie od tego, co wyśle w żądaniu.
	code, body := callAs(t, owner, "POST", ts.URL+"/api/trips", map[string]any{"title": "Spływ", "start_date": futureDate()})
	if code != 201 {
		t.Fatalf("create: %d %s", code, body)
	}
	var trip Trip
	decodeInto(t, body, &trip)
	if trip.Organizer != "owner" {
		t.Fatalf("organizer = %q", trip.Organizer)
	}
	if code, _ = callAs(t, owner, "POST", ts.URL+"/api/trips", map[string]any{"title": "x", "start_date": futureDate(), "organizer": "ktos"}); code != 400 {
		t.Errorf("pole organizer powinno być odrzucone: %d", code)
	}
	url := ts.URL + "/api/trips/" + itoa(trip.ID)

	// Osoba spoza spływu widzi go na liście, ale nie jego szczegółów ani zameldowań.
	if code, _ = callAs(t, ola, "GET", ts.URL+"/api/trips", nil); code != 200 {
		t.Errorf("lista spływów: %d", code)
	}
	for _, path := range []string{"", "/checkins"} {
		if code, _ = callAs(t, ola, "GET", url+path, nil); code != 403 {
			t.Errorf("GET %q przez nie-uczestnika: %d", path, code)
		}
	}
	if code, _ = callAs(t, ola, "POST", url+"/gear", map[string]any{"name": "Namiot"}); code != 403 {
		t.Errorf("wyposażenie przez nie-uczestnika: %d", code)
	}

	// Można dodać tylko siebie.
	if code, _ = callAs(t, ola, "POST", url+"/participants", map[string]any{"name": "Ewa"}); code != 403 {
		t.Errorf("dodanie innej osoby: %d", code)
	}
	code, body = callAs(t, ola, "POST", url+"/participants", map[string]any{"name": "ola", "car_seats": 3})
	var olaP Participant
	decodeInto(t, body, &olaP)
	if code != 201 || olaP.Name != "Ola" {
		t.Fatalf("dołączenie: %d %s", code, body)
	}
	// Powtórne dołączenie aktualizuje dane zamiast dublować uczestnika.
	code, body = callAs(t, ola, "POST", url+"/participants", map[string]any{"car_seats": 5, "needs_kayak": true})
	var again Participant
	decodeInto(t, body, &again)
	if code != 200 || again.ID != olaP.ID || again.CarSeats != 5 || !again.NeedsKayak {
		t.Fatalf("aktualizacja: %d %s", code, body)
	}

	// Uczestnik widzi szczegóły; wyposażenie można przypisać tylko uczestnikowi.
	_, body = callAs(t, ola, "GET", url, nil)
	var detail TripDetail
	decodeInto(t, body, &detail)
	if len(detail.Participants) != 2 {
		t.Fatalf("uczestnicy: %+v", detail.Participants)
	}
	// Wyposażenie układa tylko organizator.
	if code, _ = callAs(t, ola, "POST", url+"/gear", map[string]any{"name": "Namiot"}); code != 403 {
		t.Errorf("wyposażenie przez zwykłego uczestnika: %d", code)
	}
	code, body = callAs(t, owner, "POST", url+"/gear", map[string]any{"name": "Namiot", "assigned_to": "ola", "requirement": "required"})
	var gear GearItem
	decodeInto(t, body, &gear)
	if code != 201 || gear.AssignedTo == nil || *gear.AssignedTo != "Ola" {
		t.Fatalf("gear: %d %s", code, body)
	}
	if gear.Requirement != "required" {
		t.Errorf("requirement: %q", gear.Requirement)
	}
	if code, _ = callAs(t, ola, "PATCH", url+"/gear/"+itoa(gear.ID), map[string]any{"assigned_to": "Ewa"}); code != 400 {
		t.Errorf("przypisanie do nie-uczestnika: %d", code)
	}
	if code, _ = callAs(t, ola, "PATCH", url+"/gear/"+itoa(gear.ID), map[string]any{"requirement": "recommended"}); code != 403 {
		t.Errorf("zmiana wymagalności przez uczestnika: %d", code)
	}
	if code, _ = callAs(t, ola, "DELETE", url+"/gear/"+itoa(gear.ID), nil); code != 403 {
		t.Errorf("usunięcie pozycji przez uczestnika: %d", code)
	}
	// Każdy potwierdza tylko za siebie.
	code, body = callAs(t, ola, "PUT", url+"/gear/"+itoa(gear.ID)+"/confirm", map[string]any{"confirmed": true})
	var confirmed GearItem
	decodeInto(t, body, &confirmed)
	if code != 200 || len(confirmed.ConfirmedBy) != 1 || confirmed.ConfirmedBy[0] != "Ola" {
		t.Fatalf("potwierdzenie: %d %s", code, body)
	}
	if code, _ = callAs(t, ewa, "PUT", url+"/gear/"+itoa(gear.ID)+"/confirm", map[string]any{"confirmed": true}); code != 403 {
		t.Errorf("potwierdzenie przez obcego: %d", code)
	}
	if code, _ = callAs(t, ola, "PUT", url+"/gear/"+itoa(gear.ID)+"/confirm", map[string]any{}); code != 400 {
		t.Errorf("potwierdzenie bez pola: %d", code)
	}

	// Zameldować można tylko siebie.
	pos := map[string]any{"lat": 50.0, "lon": 20.0}
	if code, _ = callAs(t, ola, "POST", url+"/checkins", map[string]any{"person_name": "owner", "lat": 50.0, "lon": 20.0}); code != 403 {
		t.Errorf("zameldowanie kogoś innego: %d", code)
	}
	code, body = callAs(t, ola, "POST", url+"/checkins", pos)
	var ci CheckIn
	decodeInto(t, body, &ci)
	if code != 201 || ci.PersonName != "Ola" {
		t.Fatalf("zameldowanie: %d %s", code, body)
	}

	// Uczestnik nie usunie spływu ani innych osób; organizator nie może opuścić własnego spływu.
	if code, _ = callAs(t, ola, "DELETE", url, nil); code != 403 {
		t.Errorf("usunięcie spływu przez uczestnika: %d", code)
	}
	var ownerP Participant
	for _, p := range detail.Participants {
		if p.Name == "owner" {
			ownerP = p
		}
	}
	if code, _ = callAs(t, ola, "DELETE", url+"/participants/"+itoa(ownerP.ID), nil); code != 403 {
		t.Errorf("usunięcie organizatora przez uczestnika: %d", code)
	}
	if code, _ = callAs(t, owner, "DELETE", url+"/participants/"+itoa(olaP.ID), nil); code != 403 {
		t.Errorf("organizator usuwa innego uczestnika: %d", code)
	}
	if code, _ = callAs(t, owner, "DELETE", url+"/participants/"+itoa(ownerP.ID), nil); code != 403 {
		t.Errorf("organizator opuszcza własny spływ: %d", code)
	}
	if code, _ = callAs(t, ewa, "DELETE", url, nil); code != 403 {
		t.Errorf("usunięcie spływu przez obcego: %d", code)
	}

	// Uczestnik może usunąć siebie: traci dostęp, a przypisane mu wyposażenie wraca do puli.
	if code, _ = callAs(t, ola, "DELETE", url+"/participants/"+itoa(olaP.ID), nil); code != 204 {
		t.Fatalf("opuszczenie spływu: %d", code)
	}
	if code, _ = callAs(t, ola, "GET", url, nil); code != 403 {
		t.Errorf("dostęp po opuszczeniu: %d", code)
	}
	_, body = callAs(t, owner, "GET", url, nil)
	decodeInto(t, body, &detail)
	if len(detail.Participants) != 1 || detail.Gear[0].AssignedTo != nil {
		t.Fatalf("stan po opuszczeniu: %+v", detail)
	}

	// Tylko organizator usuwa spływ; potem wszyscy dostają 404.
	if code, _ = callAs(t, owner, "DELETE", url, nil); code != 204 {
		t.Fatalf("usunięcie spływu: %d", code)
	}
	if code, _ = callAs(t, owner, "GET", url, nil); code != 404 {
		t.Errorf("po usunięciu: %d", code)
	}
}

func TestSessionsPersistAcrossRestart(t *testing.T) {
	file := t.TempDir() + "/data.json"
	ts1, _ := newTestServer(t, file)
	token := authToken(t, ts1.URL, "trwaly")
	ts1.Close()

	ts2, _ := newTestServer(t, file)
	if code, _ := callAs(t, token, "GET", ts2.URL+"/api/me", nil); code != 200 {
		t.Fatalf("sesja po restarcie: %d", code)
	}
}
