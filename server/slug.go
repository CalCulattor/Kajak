package main

import "strings"

var polishLetters = strings.NewReplacer(
	"ą", "a", "ć", "c", "ę", "e", "ł", "l", "ń", "n",
	"ó", "o", "ś", "s", "ź", "z", "ż", "z",
)

// slugify zamienia tekst na krótki identyfikator z małych liter ASCII, cyfr i myślników
// (np. "Dunajec: Sromowce – Szczawnica" -> "dunajec-sromowce-szczawnica").
func slugify(s string, maxLen int) string {
	s = polishLetters.Replace(strings.ToLower(s))
	var b strings.Builder
	prevDash := true // nie zaczynamy od myślnika
	for _, r := range s {
		switch {
		case (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9'):
			b.WriteRune(r)
			prevDash = false
		case !prevDash:
			b.WriteByte('-')
			prevDash = true
		}
	}
	out := strings.Trim(b.String(), "-")
	if len(out) > maxLen { // wynik jest czystym ASCII, więc cięcie po bajtach jest bezpieczne
		out = strings.Trim(out[:maxLen], "-")
	}
	if out == "" {
		out = "route"
	}
	return out
}
