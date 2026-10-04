package main

import (
	"path/filepath"
	"regexp"
	"testing"
)

func validRoute() map[string]any {
	return map[string]any{
		"client_id": "r-1", "river_name": "Drawa", "region": "Zachodniopomorskie",
		"river_type": "LOWLAND", "name": "Drawno – Złocieniec", "length_km": 35.5,
		"difficulty": "WW1", "put_in": "Drawno", "take_out": "Złocieniec",
		"lat": 53.2, "lon": 16.0, "description": "Spokojny spływ",
	}
}

func TestSlugify(t *testing.T) {
	got := slugify("Dunajec: Sromowce – Szczawnica", 50)
	if got != "dunajec-sromowce-szczawnica" {
		t.Fatalf("slug: %q", got)
	}
	if got := slugify("Łódź żółć", 50); got != "lodz-zolc" {
		t.Fatalf("polskie litery: %q", got)
	}
	if got := slugify("!!!", 50); got != "route" {
		t.Fatalf("pusty: %q", got)
	}
	if got := slugify("abcdefghij", 4); got != "abcd" {
		t.Fatalf("przycięcie: %q", got)
	}
}

func TestRouteFlow(t *testing.T) {
	ts, _ := newTestServer(t, "")
	var rt Route

	code, body := call(t, "POST", ts.URL+"/api/routes", validRoute())
	if code != 201 {
		t.Fatalf("create: %d %s", code, body)
	}
	decodeInto(t, body, &rt)
	if !sectionKeyRe.MatchString(rt.Key) || !regexp.MustCompile(`^drawa-drawno-zlocieniec-\d+$`).MatchString(rt.Key) {
		t.Fatalf("klucz: %q", rt.Key)
	}

	// ten sam client_id nie tworzy duplikatu
	code, body = call(t, "POST", ts.URL+"/api/routes", validRoute())
	var again Route
	decodeInto(t, body, &again)
	if code != 200 || again.Key != rt.Key {
		t.Fatalf("dedupe: %d %s", code, body)
	}

	code, body = call(t, "GET", ts.URL+"/api/routes", nil)
	var list []Route
	decodeInto(t, body, &list)
	if code != 200 || len(list) != 1 {
		t.Fatalf("lista: %d %s", code, body)
	}

	code, _ = call(t, "GET", ts.URL+"/api/routes/"+rt.Key, nil)
	if code != 200 {
		t.Fatalf("get: %d", code)
	}
	code, _ = call(t, "GET", ts.URL+"/api/routes/brak", nil)
	if code != 404 {
		t.Fatalf("get brak: %d", code)
	}

	// klucz trasy działa jako section_key przeszkód
	code, body = call(t, "POST", ts.URL+"/api/sections/"+rt.Key+"/obstacles",
		map[string]any{"type": "WEIR", "description": "jaz"})
	if code != 201 {
		t.Fatalf("przeszkoda na trasie: %d %s", code, body)
	}
}

func TestRouteValidation(t *testing.T) {
	ts, _ := newTestServer(t, "")
	mut := func(k string, v any) map[string]any {
		m := validRoute()
		m[k] = v
		return m
	}
	bad := []map[string]any{
		mut("river_name", " "), mut("name", ""), mut("river_type", "SEA"),
		mut("difficulty", "WW9"), mut("length_km", -1), mut("length_km", 5000),
		mut("lat", 91), mut("lon", 181), mut("unknown_field", 1),
	}
	for i, b := range bad {
		if code, body := call(t, "POST", ts.URL+"/api/routes", b); code != 400 {
			t.Errorf("przypadek %d: %d %s", i, code, body)
		}
	}
	if code, _ := call(t, "POST", ts.URL+"/api/routes", "{nie json"); code != 400 {
		t.Errorf("zły JSON: %d", code)
	}
}

func TestRoutesPersist(t *testing.T) {
	file := filepath.Join(t.TempDir(), "data.json")
	ts, _ := newTestServer(t, file)
	if code, body := call(t, "POST", ts.URL+"/api/routes", validRoute()); code != 201 {
		t.Fatalf("%d %s", code, body)
	}
	st2, err := OpenStore(file)
	if err != nil {
		t.Fatal(err)
	}
	if n := len(st2.ListRoutes()); n != 1 {
		t.Fatalf("po restarcie: %d tras", n)
	}
}
