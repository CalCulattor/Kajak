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
| Konta | rejestracja i logowanie nazwą użytkownika i hasłem (bez e-maila, nazwa unikalna), ikona ustawień → „Zaloguj / zarejestruj” |
| Serwer (synchronizacja) | domyślnie `https://hackyeah.duckdns.org/`, zmiana w ustawieniach (ikona koła zębatego); trasy, przeszkody, spływy, zameldowania |

## Serwer i synchronizacja

Aplikacja działa najpierw lokalnie (Room), a serwer (`server/`, Go) służy do dzielenia się danymi.
Adres ustawiasz na ekranie **Serwer** (ikona ustawień na listach rzek i spływów); pusty adres
wyłącza synchronizację. „Testuj połączenie” sprawdza `GET /api/health`.

| Funkcja | Jak działa |
|---|---|
| Trasy | **+** na liście rzek → formularz. Trasa trafia na serwer (`POST /api/routes`); lista rzek przy wejściu i po kliknięciu odświeżenia wysyła lokalne trasy i pobiera trasy innych. Oznaczenie „Czeka na wysłanie na serwer”, dopóki się nie uda. |
| Przeszkody | zgłoszenia i głosy „nadal tu jest” / „już usunięte” są wysyłane od razu; gdy nie ma sieci, czekają i idą przy następnym odświeżeniu odcinka. Liczniki z serwera zastępują lokalne. |
| Spływy | po zalogowaniu nazwa konta jest automatycznie imieniem organizatora (bez pytania o imię). Ikona udostępniania wysyła spływ na serwer; potem ta sama ikona synchronizuje. **Spływy → Dołącz** pozwala dołączyć do spływu z serwera jako siebie. |
| Uprawnienia w spływie | usunąć spływ może tylko organizator (znika u wszystkich); uczestnik może tylko opuścić spływ (znika u niego z listy); każdy dodaje i edytuje tylko siebie; zameldować można tylko siebie. Spływ usunięty przez organizatora lub opuszczony znika z telefonu przy wejściu na listę spływów. |
| Zameldowania | w udostępnionym spływie idą na serwer od razu; w nieudostępnionym zostają na telefonie. |

Ograniczenia: bez konta aplikacja działa lokalnie i czyta trasy/przeszkody, ale wysyłanie danych i spływy
na serwerze wymagają logowania. Konto nie ma odzyskiwania hasła. Trasy i przeszkody są publiczne,
spływy widzą tylko ich uczestnicy (administrator serwera widzi wszystko). Przy synchronizacji spływu
wygrywa stan z serwera. Usunięcie lub opuszczenie udostępnionego spływu wymaga połączenia (jest
awaryjna opcja „Tylko z telefonu”, która nie zmienia serwera). Osoby wpisane ręcznie do spływu
lokalnego nie trafiają na serwer – zostają „tylko na tym telefonie”. Zalogowanie na inne konto lub
inny serwer usuwa z telefonu kopie spływów poprzedniego konta.
Trasy wymagają serwera z endpointem `/api/routes` (jest w `server/`; starsza wersja serwera zwróci 404
i aplikacja pokaże komunikat).

Aktualizacje na żywo: gdy aplikacja jest na ekranie i jesteś zalogowany, trzyma połączenie z serwerem (`GET /api/events`). Kiedy ktoś dołączy do spływu, doda sprzęt, zamelduje się albo zgłosi przeszkodę, Twój telefon sam pobiera zmianę – bez ręcznego odświeżania. Po zerwaniu połączenia aplikacja łączy się ponownie i nadrabia zaległości. Przy spływie widać, kto jest organizatorem.

## Czego jeszcze nie ma (świadomie)

- **Map offline** i rysowania trasy na mapie (pozycje otwierają się w zewnętrznej aplikacji map).
- **Rozbudowanej bazy rzek.** `SeedData.kt` zawiera dwa przykładowe odcinki; ich trudność i długość
  trzeba zweryfikować. Wodowskaz ustawia się w aplikacji (nazwa stacji jak w danych IMGW).
- Odzyskiwania hasła, zmiany hasła i usuwania konta; edycji i usuwania tras.

## Struktura

```
domain/   czysta logika (ryzyko, świeżość danych, reguły przeszkód) + testy w app/src/test
data/     Room (db/), klienty API (remote/), repozytoria
ui/       ekrany Compose, ViewModele, nawigacja
util/     lokalizacja, formatowanie
```
