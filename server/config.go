package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
)

// Config to ustawienia serwera. Host i Port są osobnymi polami.
type Config struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	DataFile string `json:"data_file"`
}

const defaultConfigPath = "config.json"

// DefaultConfig zwraca ustawienia domyślne: tylko localhost, port 8080.
func DefaultConfig() Config {
	return Config{Host: "127.0.0.1", Port: 8080, DataFile: "data.json"}
}

// Addr zwraca adres w postaci host:port, poprawny także dla IPv6.
func (c Config) Addr() string {
	return net.JoinHostPort(c.Host, strconv.Itoa(c.Port))
}

// Validate sprawdza, czy ustawienia nadają się do uruchomienia.
func (c Config) Validate() error {
	if strings.TrimSpace(c.Host) == "" {
		return errors.New("host nie może być pusty")
	}
	if c.Port < 1 || c.Port > 65535 {
		return fmt.Errorf("port %d jest poza zakresem 1-65535", c.Port)
	}
	return nil
}

// IsLoopback mówi, czy serwer nasłuchuje wyłącznie na tym komputerze.
func (c Config) IsLoopback() bool {
	if strings.EqualFold(c.Host, "localhost") {
		return true
	}
	ip := net.ParseIP(c.Host)
	return ip != nil && ip.IsLoopback()
}

// LoadConfig buduje ustawienia. Pierwszeństwo (od najsłabszego):
// wartości domyślne < plik config.json < zmienne środowiskowe < flagi.
func LoadConfig(args []string, getenv func(string) string, stderr io.Writer) (Config, error) {
	fs := flag.NewFlagSet("kajakapp-server", flag.ContinueOnError)
	fs.SetOutput(stderr)
	configPath := fs.String("config", defaultConfigPath, "ścieżka do pliku konfiguracji (JSON)")
	host := fs.String("host", "", "adres nasłuchiwania, np. 127.0.0.1")
	port := fs.Int("port", 0, "port serwera, 1-65535")
	dataFile := fs.String("data", "", "plik z danymi serwera")
	if err := fs.Parse(args); err != nil {
		return Config{}, err
	}

	set := map[string]bool{}
	fs.Visit(func(f *flag.Flag) { set[f.Name] = true })

	cfg := DefaultConfig()

	// Plik: brak domyślnego pliku jest w porządku, brak pliku podanego flagą nie.
	data, err := os.ReadFile(*configPath)
	switch {
	case err == nil:
		dec := json.NewDecoder(strings.NewReader(string(data)))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&cfg); err != nil {
			return Config{}, fmt.Errorf("plik %s: %w", *configPath, err)
		}
	case errors.Is(err, os.ErrNotExist) && !set["config"]:
		// używamy wartości domyślnych
	default:
		return Config{}, fmt.Errorf("nie można odczytać %s: %w", *configPath, err)
	}

	if v := getenv("KAJAK_HOST"); v != "" {
		cfg.Host = v
	}
	if v := getenv("KAJAK_PORT"); v != "" {
		p, err := strconv.Atoi(v)
		if err != nil {
			return Config{}, fmt.Errorf("KAJAK_PORT=%q nie jest liczbą", v)
		}
		cfg.Port = p
	}
	if v := getenv("KAJAK_DATA_FILE"); v != "" {
		cfg.DataFile = v
	}

	if set["host"] {
		cfg.Host = *host
	}
	if set["port"] {
		cfg.Port = *port
	}
	if set["data"] {
		cfg.DataFile = *dataFile
	}

	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}
