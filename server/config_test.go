package main

import (
	"io"
	"path/filepath"
	"testing"
)

func noEnv(string) string { return "" }

func envOf(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

// W testach używamy nieistniejącego pliku konfiguracji, żeby nie zależeć od config.json w katalogu.
func missingConfig(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "brak.json")
}

func TestConfigDefaults(t *testing.T) {
	// Bez flagi -config brak domyślnego pliku jest dozwolony.
	chdir(t, t.TempDir())
	cfg, err := LoadConfig(nil, noEnv, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Host != "127.0.0.1" || cfg.Port != 8080 || cfg.Addr() != "127.0.0.1:8080" {
		t.Fatalf("domyślne ustawienia: %+v", cfg)
	}
	if !cfg.IsLoopback() {
		t.Fatal("127.0.0.1 powinien być lokalny")
	}
}

func TestConfigPrecedence(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "config.json")
	if err := writeFile(file, `{"host":"localhost","port":9000,"data_file":"z-pliku.json"}`); err != nil {
		t.Fatal(err)
	}

	// Sam plik.
	cfg, err := LoadConfig([]string{"-config", file}, noEnv, io.Discard)
	if err != nil || cfg.Port != 9000 || cfg.Host != "localhost" || cfg.DataFile != "z-pliku.json" {
		t.Fatalf("plik: %+v %v", cfg, err)
	}
	// Zmienna środowiskowa przebija plik.
	cfg, err = LoadConfig([]string{"-config", file}, envOf(map[string]string{"KAJAK_PORT": "9100"}), io.Discard)
	if err != nil || cfg.Port != 9100 {
		t.Fatalf("env: %+v %v", cfg, err)
	}
	// Flaga przebija zmienną środowiskową.
	cfg, err = LoadConfig([]string{"-config", file, "-port", "9200"}, envOf(map[string]string{"KAJAK_PORT": "9100"}), io.Discard)
	if err != nil || cfg.Port != 9200 || cfg.Host != "localhost" {
		t.Fatalf("flaga: %+v %v", cfg, err)
	}
}

func TestConfigPartialFileKeepsDefaults(t *testing.T) {
	file := filepath.Join(t.TempDir(), "config.json")
	if err := writeFile(file, `{"port": 7000}`); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig([]string{"-config", file}, noEnv, io.Discard)
	if err != nil || cfg.Port != 7000 || cfg.Host != "127.0.0.1" {
		t.Fatalf("%+v %v", cfg, err)
	}
}

func TestConfigRejectsBadValues(t *testing.T) {
	missing := missingConfig(t)
	cases := []struct {
		name string
		args []string
		env  map[string]string
	}{
		{"port 0", []string{"-config", writeTemp(t, `{"port":0}`)}, nil},
		{"port za duży", []string{"-port", "70000"}, nil},
		{"port ujemny", []string{"-port", "-5"}, nil},
		{"env nie liczba", nil, map[string]string{"KAJAK_PORT": "abc"}},
		{"pusty host", []string{"-host", ""}, nil},
		{"brak pliku podanego flagą", []string{"-config", missing}, nil},
		{"nieznane pole w pliku", []string{"-config", writeTemp(t, `{"prot":1}`)}, nil},
		{"zepsuty plik", []string{"-config", writeTemp(t, `{`)}, nil},
	}
	for _, c := range cases {
		args := c.args
		if len(args) == 0 || args[0] != "-config" {
			args = append([]string{"-config", writeTemp(t, `{}`)}, args...)
		}
		if _, err := LoadConfig(args, envOf(c.env), io.Discard); err == nil {
			t.Errorf("%s: oczekiwano błędu", c.name)
		}
	}
}

func writeTemp(t *testing.T, content string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "c.json")
	if err := writeFile(p, content); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestAddrIPv6AndLoopback(t *testing.T) {
	c := Config{Host: "::1", Port: 8081}
	if c.Addr() != "[::1]:8081" {
		t.Fatalf("adres IPv6: %s", c.Addr())
	}
	if !c.IsLoopback() {
		t.Fatal("::1 powinien być lokalny")
	}
	if (Config{Host: "0.0.0.0", Port: 1}).IsLoopback() {
		t.Fatal("0.0.0.0 nie jest lokalny")
	}
}
