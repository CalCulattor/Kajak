package main

import (
	"path/filepath"
	"testing"
	"time"
)

func participantByName(t *testing.T, base, token, name string) Participant {
	t.Helper()
	_, body := callAs(t, token, "GET", base, nil)
	var d TripDetail
	decodeInto(t, body, &d)
	for _, p := range d.Participants {
		if p.Name == name {
			return p
		}
	}
	t.Fatalf("brak uczestnika %s", name)
	return Participant{}
}

func TestCoOrganizers(t *testing.T) {
	ts, _ := newTestServer(t, "")
	owner := authToken(t, ts.URL, "owner")
	ola := authToken(t, ts.URL, "Ola")
	ewa := authToken(t, ts.URL, "Ewa")

	code, body := callAs(t, owner, "POST", ts.URL+"/api/trips", map[string]any{"title": "Spływ", "start_date": futureDate()})
	if code != 201 {
		t.Fatalf("create: %d %s", code, body)
	}
	var trip Trip
	decodeInto(t, body, &trip)
	url := ts.URL + "/api/trips/" + itoa(trip.ID)
	callAs(t, ola, "POST", url+"/participants", map[string]any{})
	callAs(t, ewa, "POST", url+"/participants", map[string]any{})

	ownerP := participantByName(t, url, owner, "owner")
	olaP := participantByName(t, url, owner, "Ola")
	ewaP := participantByName(t, url, owner, "Ewa")
	if !ownerP.IsOrganizer || olaP.IsOrganizer {
		t.Fatalf("role na starcie: %+v %+v", ownerP, olaP)
	}

	// Jedyny organizator nie może odejść, ale zwykły uczestnik nie może nikogo mianować.
	if code, _ = callAs(t, owner, "DELETE", url+"/participants/"+itoa(ownerP.ID), nil); code != 403 {
		t.Fatalf("jedyny organizator wyszedł: %d", code)
	}
	if code, _ = callAs(t, ola, "POST", url+"/participants/"+itoa(ewaP.ID)+"/organizer", nil); code != 403 {
		t.Fatalf("uczestnik mianuje: %d", code)
	}
	if code, _ = callAs(t, owner, "POST", url+"/participants/99999/organizer", nil); code != 404 {
		t.Fatalf("nieistniejący uczestnik: %d", code)
	}

	// Organizator mianuje Olę (powtórka jest bezpieczna).
	for i := 0; i < 2; i++ {
		if code, body = callAs(t, owner, "POST", url+"/participants/"+itoa(olaP.ID)+"/organizer", nil); code != 200 {
			t.Fatalf("mianowanie: %d %s", code, body)
		}
	}
	_, body = callAs(t, ewa, "GET", url, nil)
	var d TripDetail
	decodeInto(t, body, &d)
	if len(d.Trip.Organizers) != 2 {
		t.Fatalf("organizatorzy: %v", d.Trip.Organizers)
	}

	// Ola jako organizator może mianować Ewę, a zwykły uczestnik nadal nie może usunąć spływu.
	if code, _ = callAs(t, ola, "POST", url+"/participants/"+itoa(ewaP.ID)+"/organizer", nil); code != 200 {
		t.Fatalf("Ola mianuje: %d", code)
	}

	// Teraz twórca może odejść – spływ zostaje, a organizatorem jest dalej Ola.
	if code, _ = callAs(t, owner, "DELETE", url+"/participants/"+itoa(ownerP.ID), nil); code != 204 {
		t.Fatalf("wyjście twórcy: %d", code)
	}
	if code, _ = callAs(t, owner, "GET", url, nil); code != 403 {
		t.Fatalf("były uczestnik widzi spływ: %d", code)
	}
	_, body = callAs(t, ola, "GET", url, nil)
	decodeInto(t, body, &d)
	if d.Trip.Organizer != "Ola" || len(d.Trip.Organizers) != 2 || len(d.Participants) != 2 {
		t.Fatalf("po wyjściu: %+v", d)
	}
	// Lista spływów też pokazuje aktualnego organizatora.
	_, body = callAs(t, ola, "GET", ts.URL+"/api/trips", nil)
	var list []Trip
	decodeInto(t, body, &list)
	if len(list) != 1 || list[0].Organizer != "Ola" {
		t.Fatalf("lista: %+v", list)
	}

	// Współorganizator może usunąć spływ, wyjść może też kolejny, ale nie ostatni.
	olaP = participantByName(t, url, ola, "Ola")
	ewaP = participantByName(t, url, ola, "Ewa")
	if code, _ = callAs(t, ola, "DELETE", url+"/participants/"+itoa(olaP.ID), nil); code != 204 {
		t.Fatalf("Ola wychodzi: %d", code)
	}
	if code, _ = callAs(t, ewa, "DELETE", url+"/participants/"+itoa(ewaP.ID), nil); code != 403 {
		t.Fatalf("ostatnia organizatorka wyszła: %d", code)
	}
	if code, _ = callAs(t, ewa, "DELETE", url, nil); code != 204 {
		t.Fatalf("organizatorka usuwa spływ: %d", code)
	}
}

func TestOnlyOrganizerDeletes(t *testing.T) {
	ts, _ := newTestServer(t, "")
	owner := authToken(t, ts.URL, "owner")
	ola := authToken(t, ts.URL, "Ola")
	_, body := callAs(t, owner, "POST", ts.URL+"/api/trips", map[string]any{"title": "Spływ", "start_date": futureDate()})
	var trip Trip
	decodeInto(t, body, &trip)
	url := ts.URL + "/api/trips/" + itoa(trip.ID)
	callAs(t, ola, "POST", url+"/participants", map[string]any{})
	if code, _ := callAs(t, ola, "DELETE", url, nil); code != 403 {
		t.Fatalf("uczestnik usunął spływ: %d", code)
	}
}

func TestLegacyTripCreatorBecomesOrganizer(t *testing.T) {
	file := filepath.Join(t.TempDir(), "data.json")
	st, err := OpenStore(file)
	if err != nil {
		t.Fatal(err)
	}
	trip, _ := st.AddTrip(Trip{Title: "Stary", StartDate: "2026-01-01", Organizer: "Marek"})
	st.AddParticipant(trip.ID, Participant{Name: "Marek"}) // dane sprzed zmiany: bez is_organizer
	st.AddParticipant(trip.ID, Participant{Name: "Ola"})

	st2, err := OpenStore(file)
	if err != nil {
		t.Fatal(err)
	}
	if a := st2.Access(trip.ID, "marek"); !a.Organizer {
		t.Fatal("twórca starego spływu powinien być organizatorem")
	}
	if a := st2.Access(trip.ID, "Ola"); a.Organizer {
		t.Fatal("Ola nie powinna być organizatorem")
	}
}

func TestPastDatesRejected(t *testing.T) {
	ts, _ := newTestServer(t, "")
	create := func(date string) int {
		code, _ := call(t, "POST", ts.URL+"/api/trips", map[string]any{"title": "T", "start_date": date})
		return code
	}
	today := time.Now().UTC()
	if code := create(today.AddDate(0, 0, -5).Format("2006-01-02")); code != 400 {
		t.Errorf("data sprzed 5 dni: %d", code)
	}
	if code := create("2020-01-01"); code != 400 {
		t.Errorf("data z 2020: %d", code)
	}
	if code := create(today.Format("2006-01-02")); code != 201 {
		t.Errorf("dzisiaj: %d", code)
	}
	if code := create(today.AddDate(0, 0, 1).Format("2006-01-02")); code != 201 {
		t.Errorf("jutro: %d", code)
	}
}
