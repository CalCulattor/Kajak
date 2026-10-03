package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "błąd:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	cfg, err := LoadConfig(args, os.Getenv, os.Stderr)
	if err != nil {
		return err
	}

	store, err := OpenStore(cfg.DataFile)
	if err != nil {
		return err
	}

	// Najpierw zajmujemy port, żeby błąd (np. port zajęty) był czytelny i od razu widoczny.
	ln, err := net.Listen("tcp", cfg.Addr())
	if err != nil {
		return fmt.Errorf("nie można nasłuchiwać na %s: %w", cfg.Addr(), err)
	}

	httpSrv := &http.Server{
		Handler:           NewServer(store).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	log.Printf("serwer KajakApp: http://%s (dane: %s)", ln.Addr(), cfg.DataFile)
	if !cfg.IsLoopback() {
		log.Printf("UWAGA: host %q nie jest adresem lokalnym, a API nie ma uwierzytelniania", cfg.Host)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() { errCh <- httpSrv.Serve(ln) }()

	select {
	case err := <-errCh:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	case <-ctx.Done():
		log.Println("zamykanie serwera...")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return httpSrv.Shutdown(shutdownCtx)
	}
}
