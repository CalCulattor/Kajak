package main

import (
	"fmt"
	"log"
	"net/http"
)

func main() {
	// Catch-all handler: kazda sciezka (/, /foo, /a/b/c) zwraca "hello world".
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		log.Printf("%s %s (host: %s)", r.Method, r.URL.String(), r.Host)
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprint(w, "hello world")
	})

	log.Println("Serwer dziala na http://localhost:9000")
	log.Fatal(http.ListenAndServe(":9000", nil))
}
