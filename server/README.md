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
(np. `0.0.0.0`, żeby połączyć się z telefonu w tej samej sieci), pamiętaj, że hasła i tokeny
przechodzą wtedy przez sieć jako zwykły tekst – **do użytku poza localhostem postaw serwer za HTTPS**
(reverse proxy z TLS). Serwer ostrzeże o tym w logu.

## Łączenie z emulatora Androida

Emulator widzi komputer pod adresem `10.0.2.2`, a nie `localhost`. Adres serwera w aplikacji
to więc `http://10.0.2.2:8080`. Aplikacja ma konfigurację sieci (`network_security_config.xml`), która
dopuszcza HTTP bez szyfrowania tylko dla `10.0.2.2` i `localhost`; wszystko inne musi być HTTPS.

## API

Wszystkie odpowiedzi to JSON. Błędy mają postać `{"error": "..."}`.

| Metoda i ścieżka | Opis |
| --- | --- |
| `GET /api/health` | Sprawdzenie, czy serwer działa (bez logowania). |
| `POST /api/register` | Nowe konto: `username` (3–24 znaki: a-z, A-Z, 0-9, `_ . -`; unikalne bez względu na wielkość liter) i `password` (8–128 znaków). Zwraca `{"token","username"}`; 409 gdy nazwa zajęta. |
| `POST /api/login` | Logowanie tym samym ciałem. 401 przy złych danych, 429 po 8 nieudanych próbach na konto (blokada na 10 min). |
| `POST /api/logout`, `GET /api/me` | Unieważnienie bieżącego tokenu i sprawdzenie, kim jestem. |
| `GET /api/routes`, `POST /api/routes` | Lista tras i dodanie trasy (`river_name`, `region`, `river_type` = `LOWLAND`/`MOUNTAIN`, `name`, `length_km`, `difficulty` = `FLAT`/`WW1`…`WW5`, `put_in`, `take_out`, `lat`, `lon`, `description`, opcjonalnie `station_name`, `client_id`). Serwer nadaje stabilny `key` (np. `drawa-drawno-zlocieniec-7`), którego używają przeszkody i spływy. |
| `GET /api/routes/{key}` | Pojedyncza trasa. |
| `GET /api/sections/{key}/obstacles` | Aktywne przeszkody odcinka (`?include_inactive=true` pokazuje też usunięte). |
| `POST /api/sections/{key}/obstacles` | Zgłoszenie przeszkody: `type`, `description`, opcjonalnie `lat` i `lon` oraz `client_id`. |
| `POST /api/obstacles/{id}/confirm` | „Nadal tu jest”. |
| `POST /api/obstacles/{id}/remove-vote` | „Już usunięte”. |
| `GET /api/trips`, `POST /api/trips` | Lista (każdy zalogowany) i tworzenie spływów (`title`, `start_date` jako `RRRR-MM-DD`, opcjonalnie `section_key`, `overnight`, `notes`). Organizatorem jest zawsze zalogowany użytkownik i zostaje pierwszym uczestnikiem. |
| `GET /api/trips/{id}`, `DELETE /api/trips/{id}` | Szczegóły (z uczestnikami, wyposażeniem, zameldowaniami) – tylko dla uczestników (inni dostają 403). Usunąć spływ z całą zawartością może tylko organizator. |
| `POST /api/trips/{id}/participants`, `DELETE .../participants/{pid}` | Uczestnicy. `POST` dodaje **zawsze zalogowanego użytkownika** (`car_seats`, `needs_kayak`; opcjonalne `name` musi być zgodne z kontem, inaczej 403); ponowne wywołanie aktualizuje jego dane. `DELETE` pozwala usunąć tylko siebie; organizator nie może opuścić własnego spływu (może go usunąć). |
| `POST /api/trips/{id}/gear`, `PATCH`, `DELETE .../gear/{gid}` | Wyposażenie (tylko uczestnicy; `assigned_to` musi być uczestnikiem spływu). `PATCH` przyjmuje `packed` i/lub `assigned_to` (`null` czyści przypisanie). |
| `GET /api/trips/{id}/checkins`, `POST` | Zameldowania (tylko uczestnicy; można zameldować tylko siebie): `lat`, `lon`, opcjonalnie `fix_at`, `needs_help`, `client_id` (`person_name` opcjonalne, musi zgadzać się z kontem). Lista jest od najnowszego. |

Wymagają logowania (nagłówek `Authorization: Bearer <token>`): wszystkie zapisy (trasy, przeszkody, głosy),
lista i szczegóły spływów. Publiczne są tylko `GET` tras i przeszkód oraz `health`. Hasła są
przechowywane jako PBKDF2-HMAC-SHA256 z solą (600 000 iteracji), tokeny jako skrót SHA-256; sesja
ważna 90 dni.

Typy przeszkód: `STRAINER`, `WEIR`, `LOW_BRIDGE`, `ROCK_SIEVE`, `PORTAGE`, `OTHER`.
Reguły są takie jak w aplikacji: przeszkoda przestaje być aktywna, gdy ma co najmniej 2 zgłoszenia usunięcia
i więcej niż potwierdzeń.

`client_id` służy do bezpiecznego ponawiania wysyłki po utracie zasięgu. Powtórzone żądanie z tym samym
`client_id` nie tworzy duplikatu, tylko zwraca istniejący wpis (kod 200 zamiast 201).

### Przykłady

```
curl http://127.0.0.1:8080/api/health

# rejestracja i zapamiętanie tokenu
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/register -H 'Content-Type: application/json' \
  -d '{"username":"marcel","password":"tajne-haslo-1"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -X POST http://127.0.0.1:8080/api/sections/dunajec-przelom/obstacles \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"STRAINER","description":"drzewo przy lewym brzegu","lat":49.4,"lon":20.4}'

curl -X POST http://127.0.0.1:8080/api/trips \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Weekend na Krutyni","section_key":"krutynia","start_date":"2026-10-10","overnight":true}'
```

## Czego jeszcze brakuje

- Odzyskiwania hasła (konta nie mają e-maila), zmiany hasła i usuwania konta.
- Limitu głosów na osobę przy przeszkodach (jedno konto może głosować wielokrotnie).
- Dostępu organizatora do usuwania innych uczestników.
- Prawdziwej bazy danych zamiast pliku JSON oraz własnego TLS (na razie za reverse proxy).
- **Uwaga przy aktualizacji:** dane z wersji bez kont (`data.json` ze spływami, których organizatorem
  jest dowolny tekst) nie pasują do nowego modelu. Przed wdrożeniem usuń stary `data.json`.
