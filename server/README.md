# Serwer KajakApp (Go)

Prosty serwer REST na `localhost`, napisany wyłącznie w bibliotece standardowej Go (bez zależności).
Obsługuje po stronie serwera to, co aplikacja zapisuje dziś lokalnie: przeszkody, spływy, uczestników,
wyposażenie i zameldowania. Dane trzyma w pliku JSON (`data.json`), więc wystarczy do pracy lokalnej
i testów, ale nie do produkcji.

## Uruchomienie

Wymagane: Go 1.22 lub nowszy.

```
cd server
go run .                 # http://127.0.0.1:8080
go run . -port 9000      # inny port
go test ./...            # testy
```

## Host i port (osobne pola)

Host i port to dwa osobne ustawienia. Kolejność ważności, od najsłabszego do najmocniejszego:

| Źródło | Host | Port | Plik danych |
| --- | --- | --- | --- |
| Domyślnie | `127.0.0.1` | `8080` | `data.json` |
| Plik `config.json` | `"host"` | `"port"` | `"data_file"` |
| Zmienna środowiskowa | `KAJAK_HOST` | `KAJAK_PORT` | `KAJAK_DATA_FILE` |
| Flaga | `-host` | `-port` | `-data` |

Inny plik konfiguracji wskażesz flagą `-config ścieżka.json`. Port musi mieścić się w zakresie 1–65535,
a gdy jest zajęty, serwer kończy działanie z czytelnym komunikatem.

Domyślnie serwer słucha tylko na `127.0.0.1`, więc jest niewidoczny z sieci. Jeśli ustawisz inny host
(np. `0.0.0.0`, żeby połączyć się z telefonu w tej samej sieci), pamiętaj, że **API nie ma uwierzytelniania**
i serwer ostrzeże o tym w logu.

## Łączenie z emulatora Androida

Emulator widzi komputer pod adresem `10.0.2.2`, a nie `localhost`. Adres serwera w aplikacji
to więc `http://10.0.2.2:8080`. Ruch HTTP (bez TLS) wymaga na Androidzie 9+ zgody w manifeście
(`android:usesCleartextTraffic="true"` lub konfiguracja bezpieczeństwa sieci).

## API

Wszystkie odpowiedzi to JSON. Błędy mają postać `{"error": "..."}`.

| Metoda i ścieżka | Opis |
| --- | --- |
| `GET /api/health` | Sprawdzenie, czy serwer działa. |
| `GET /api/sections/{key}/obstacles` | Aktywne przeszkody odcinka (`?include_inactive=true` pokazuje też usunięte). |
| `POST /api/sections/{key}/obstacles` | Zgłoszenie przeszkody: `type`, `description`, opcjonalnie `lat` i `lon` oraz `client_id`. |
| `POST /api/obstacles/{id}/confirm` | „Nadal tu jest”. |
| `POST /api/obstacles/{id}/remove-vote` | „Już usunięte”. |
| `GET /api/trips`, `POST /api/trips` | Lista i tworzenie spływów (`title`, `start_date` jako `RRRR-MM-DD`, `organizer`, opcjonalnie `section_key`, `overnight`, `notes`). Organizator zostaje pierwszym uczestnikiem. |
| `GET /api/trips/{id}`, `DELETE /api/trips/{id}` | Szczegóły (z uczestnikami, wyposażeniem, zameldowaniami) i usunięcie spływu z całą zawartością. |
| `POST /api/trips/{id}/participants`, `DELETE .../participants/{pid}` | Uczestnicy: `name`, `car_seats`, `needs_kayak`. |
| `POST /api/trips/{id}/gear`, `PATCH`, `DELETE .../gear/{gid}` | Wyposażenie. `PATCH` przyjmuje `packed` i/lub `assigned_to` (`null` czyści przypisanie). |
| `GET /api/trips/{id}/checkins`, `POST` | Zameldowania: `person_name`, `lat`, `lon`, opcjonalnie `fix_at`, `needs_help`, `client_id`. Lista jest od najnowszego. |

Typy przeszkód: `STRAINER`, `WEIR`, `LOW_BRIDGE`, `ROCK_SIEVE`, `PORTAGE`, `OTHER`.
Reguły są takie jak w aplikacji: przeszkoda przestaje być aktywna, gdy ma co najmniej 2 zgłoszenia usunięcia
i więcej niż potwierdzeń.

`client_id` służy do bezpiecznego ponawiania wysyłki po utracie zasięgu. Powtórzone żądanie z tym samym
`client_id` nie tworzy duplikatu, tylko zwraca istniejący wpis (kod 200 zamiast 201).

### Przykłady

```
curl http://127.0.0.1:8080/api/health

curl -X POST http://127.0.0.1:8080/api/sections/dunajec-przelom/obstacles \
  -H 'Content-Type: application/json' \
  -d '{"type":"STRAINER","description":"drzewo przy lewym brzegu","lat":49.4,"lon":20.4}'

curl -X POST http://127.0.0.1:8080/api/trips \
  -H 'Content-Type: application/json' \
  -d '{"title":"Weekend na Krutyni","section_key":"krutynia","start_date":"2026-10-10","overnight":true,"organizer":"Marcel"}'
```

## Czego jeszcze brakuje

- **Aplikacja nie rozmawia jeszcze z tym serwerem.** Trzeba dopisać klienta HTTP i synchronizację
  rekordów oznaczonych `pendingSync`.
- **Stabilne klucze odcinków.** Serwer identyfikuje odcinki kluczem tekstowym (`section_key`), a w aplikacji
  odcinki mają dziś lokalne numery z bazy telefonu, różne na różnych urządzeniach. Do synchronizacji potrzebne
  jest pole z kluczem w `SectionEntity` i w `SeedData.kt`.
- Uwierzytelnianie, konta i limit głosów na osobę.
- Prawdziwa baza danych zamiast pliku JSON oraz TLS, zanim serwer wyjdzie poza `localhost`.
