# KajakApp

Aplikacja na Androida (Kotlin, Jetpack Compose, Room, Retrofit) dla kajakarzy: stan wody,
pogoda z oceną ryzyka, przeszkody zgłaszane przez społeczność oraz organizacja spływów.

## Uruchomienie

1. Otwórz folder w **Android Studio** (Ladybug lub nowsze, JDK 17). Studio samo pobierze Gradle 8.9
   na podstawie `gradle/wrapper/gradle-wrapper.properties` i zsynchronizuje projekt.
   (Terminalowy `./gradlew` pojawi się po wykonaniu raz `gradle wrapper` w tym folderze.)
2. Uruchom konfigurację `app` na emulatorze lub telefonie (Android 8.0+, `minSdk 26`).
3. Testy jednostkowe logiki ryzyka: `./gradlew :app:testDebugUnitTest`.

## Co jest zaimplementowane

| Obszar | Stan |
|---|---|
| Rzeki i odcinki (spływ odcinkowo) | lista, opis, trudność, start/meta; przykładowe dane startowe w `SeedData.kt` |
| Stan wody | pobierany z publicznego API IMGW (`danepubliczne.imgw.pl`), z progami ostrzegawczym i alarmowym stacji |
| Pogoda | Open-Meteo (porywy wiatru, opady, burze, temperatura) |
| Ocena ryzyka | `domain/RiskAssessor.kt`: „sprzyjające / podwyższone / skrajne / brak danych” z uzasadnieniem i zastrzeżeniem, nigdy „bezpiecznie” |
| Dane offline | ostatni odczyt jest zapisywany w bazie; dane starsze niż 6 godz. są pokazywane, ale **nie** wchodzą do oceny ryzyka |
| Przeszkody | zgłaszanie (z pozycją GPS), „nadal tu jest” / „już usunięte”, zniknięcie po 2 zgłoszeniach usunięcia, oznaczenie zgłoszeń nieweryfikowanych 30+ dni |
| Spływy | uczestnicy, miejsca w autach vs liczba osób, kajaki do wypożyczenia, lista wyposażenia z przypisaniem osób (propozycje także dla noclegu) |
| Zameldowanie / pomoc | zapis pozycji GPS (świeżej, a gdy się nie da – ostatniej znanej, z informacją o jej wieku) i otwarcie w aplikacji map |
| Dodawanie tras | przycisk **+** na liście rzek; trasa zapisuje się lokalnie i jest wysyłana na serwer |
| Serwer (synchronizacja) | domyślnie `https://hackyeah.duckdns.org/`, zmiana w ustawieniach (ikona koła zębatego); trasy, przeszkody, spływy, zameldowania |

## Serwer i synchronizacja

Aplikacja działa najpierw lokalnie (Room), a serwer (`server/`, Go) służy do dzielenia się danymi.
Adres ustawiasz na ekranie **Serwer** (ikona ustawień na listach rzek i spływów); pusty adres
wyłącza synchronizację. „Testuj połączenie” sprawdza `GET /api/health`.

| Funkcja | Jak działa |
|---|---|
| Trasy | **+** na liście rzek → formularz. Trasa trafia na serwer (`POST /api/routes`); lista rzek przy wejściu i po kliknięciu odświeżenia wysyła lokalne trasy i pobiera trasy innych. Oznaczenie „Czeka na wysłanie na serwer”, dopóki się nie uda. |
| Przeszkody | zgłoszenia i głosy „nadal tu jest” / „już usunięte” są wysyłane od razu; gdy nie ma sieci, czekają i idą przy następnym odświeżeniu odcinka. Liczniki z serwera zastępują lokalne. |
| Spływy | ikona udostępniania w spływie wysyła go na serwer (z uczestnikami, wyposażeniem i zameldowaniami); potem ta sama ikona synchronizuje. **Spływy → Dołącz** pokazuje spływy z serwera. |
| Zameldowania | w udostępnionym spływie idą na serwer od razu; w nieudostępnionym zostają na telefonie. |

Ograniczenia: serwer nie ma logowania (dane widzi każdy, kto zna adres), przy synchronizacji spływu
wygrywa stan z serwera, a usunięcie uczestnika/wyposażenia wykonane bez sieci może wrócić po
synchronizacji. Lokalne usunięcie spływu nie usuwa go z serwera.
Trasy wymagają serwera z endpointem `/api/routes` (jest w `server/`; starsza wersja serwera zwróci 404
i aplikacja pokaże komunikat).

## Czego jeszcze nie ma (świadomie)

- **Map offline** i rysowania trasy na mapie (pozycje otwierają się w zewnętrznej aplikacji map).
- **Rozbudowanej bazy rzek.** `SeedData.kt` zawiera dwa przykładowe odcinki; ich trudność i długość
  trzeba zweryfikować. Wodowskaz ustawia się w aplikacji (nazwa stacji jak w danych IMGW).
- Ekranu logowania i kont użytkowników; edycji i usuwania tras.

## Struktura

```
domain/   czysta logika (ryzyko, świeżość danych, reguły przeszkód) + testy w app/src/test
data/     Room (db/), klienty API (remote/), repozytoria
ui/       ekrany Compose, ViewModele, nawigacja
util/     lokalizacja, formatowanie
```
