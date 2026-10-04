package main

import (
	"os"
	"strconv"
	"testing"
)

// chdir zmienia katalog roboczy na czas testu (t.Chdir wymaga Go 1.24, my wspieramy 1.22).
func chdir(t *testing.T, dir string) {
	t.Helper()
	old, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(dir); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(old) })
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func writeFile(path, content string) error { return os.WriteFile(path, []byte(content), 0o600) }
