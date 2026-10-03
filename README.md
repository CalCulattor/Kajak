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

## Czego jeszcze nie ma (świadomie)

- **Serwera.** Zgłoszenia przeszkód i zameldowania są zapisywane tylko na telefonie
  (pola `pendingSync` czekają na synchronizację). Dlatego „szukam ekipy”, wspólne listy
  między telefonami i powiadomienia dla grupy wymagają backendu (np. Firebase lub własne API).
- **Map offline** i rysowania trasy na mapie (pozycje otwierają się w zewnętrznej aplikacji map).
- **Rozbudowanej bazy rzek.** `SeedData.kt` zawiera dwa przykładowe odcinki; ich trudność i długość
  trzeba zweryfikować. Wodowskaz ustawia się w aplikacji (nazwa stacji jak w danych IMGW).
- Ekranu logowania i kont użytkowników.

## Serwer (opcjonalny)

W folderze `server/` jest serwer REST w Go na `localhost` z osobnymi polami host i port.
Aplikacja jeszcze się z nim nie łączy. Uruchomienie i opis API: `server/README.md`.

## Struktura

```
domain/   czysta logika (ryzyko, świeżość danych, reguły przeszkód) + testy w app/src/test
data/     Room (db/), klienty API (remote/), repozytoria
ui/       ekrany Compose, ViewModele, nawigacja
util/     lokalizacja, formatowanie
```
