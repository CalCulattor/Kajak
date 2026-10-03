# KajakApp – instrukcja i dokumentacja

Oct 3, 2026 · @Marcel

## O aplikacji

KajakApp to aplikacja na Androida (8.0 i nowszy), która w jednym miejscu pokazuje stan wody, pogodę i zgłoszone przeszkody oraz pomaga zorganizować spływ. Zamiast „bezpiecznie / niebezpiecznie” podaje poziom ryzyka z uzasadnieniem. Decyzję o wejściu na wodę zawsze podejmuje kajakarz.

**Co działa w tej wersji**

- Rzeki i odcinki z trudnością, długością oraz miejscem startu i mety.
- Stan wody z wodowskazów IMGW, razem z progami ostrzegawczym i alarmowym.
- Skrót pogody: porywy wiatru, opady, burze, temperatura.
- Ocena ryzyka z powodami, liczona z wody, pogody i przeszkód.
- Przeszkody zgłaszane i weryfikowane przez użytkowników.
- Spływy: uczestnicy, miejsca w autach, lista wyposażenia, zameldowanie z GPS.

**Czego jeszcze nie ma**

- Serwera. Zgłoszenia i zameldowania zapisują się tylko na tym telefonie, więc nie trafiają do reszty grupy.
- Map offline. Pozycje otwierają się w zewnętrznej aplikacji map.
- Kont użytkowników i ogłoszeń „szukam ekipy”.
- Rozbudowanej bazy rzek: na start są dwa przykładowe odcinki, których opisy trzeba zweryfikować.

## Szybki start

Po instalacji aplikacja od razu działa: przy pierwszym uruchomieniu zapisuje dwa przykładowe odcinki, a dane o wodzie i pogodzie pobiera po wejściu w odcinek.

1. Uruchom aplikację. Na dole są dwie zakładki: **Rzeki** i **Spływy**.
2. W zakładce **Rzeki** dotknij odcinka, np. „Sromowce – Szczawnica”.
3. Poczekaj chwilę na pobranie danych. Pasek pod nagłówkiem pokazuje, że trwa odświeżanie.
4. Przeczytaj kolorowy baner na górze: to ocena ryzyka z powodami.
5. Jeśli chcesz zaplanować wyjazd, przejdź do zakładki **Spływy** i dotknij **+**.

**Skąd biorą się dane**

| Dane | Źródło | Uwagi |
| --- | --- | --- |
| Stan wody, przepływ, temperatura wody, progi | IMGW (danepubliczne.imgw.pl) | Dostępne tylko dla odcinka z przypisanym wodowskazem. |
| Pogoda | Open-Meteo | Prognoza na dziś dla współrzędnych odcinka. |
| Przeszkody, spływy, zameldowania | Wpisują użytkownicy | Zapisywane lokalnie na telefonie. |

**Uprawnienia**

Aplikacja używa internetu oraz lokalizacji. Lokalizację pyta dopiero wtedy, gdy zgłaszasz przeszkodę z pozycją albo się meldujesz. Jeśli odmówisz, reszta aplikacji działa normalnie.

**Bez internetu**

Ostatnio pobrane dane zostają na telefonie i są pokazywane z informacją, ile temu je pobrano. Dane starsze niż 6 godzin są oznaczone na czerwono i nie wchodzą do oceny ryzyka.

## Rzeki i odcinki: jak używać

Zakładka **Rzeki** pokazuje rzeki z ich odcinkami. Dotknięcie odcinka otwiera ekran z oceną ryzyka, wodą, pogodą i przeszkodami.

### Ekran odcinka, od góry do dołu

1. **Baner z oceną ryzyka.** Kolor mówi o poziomie, a lista pod nim o powodach (patrz niżej).
2. **Woda.** Wodowskaz, stan wody w cm, przepływ, temperatura wody, progi ostrzegawczy i alarmowy, godzina pomiaru.
3. **Pogoda.** Temperatura powietrza, porywy wiatru (m/s i km/h), opady za dziś, informacja o burzach.
4. **Odcinek.** Rzeka, trudność, długość, miejsce startu i mety, krótki opis.
5. **Przeszkody.** Lista zgłoszeń i przycisk **Zgłoś przeszkodę**.

Przycisk odświeżania w prawym górnym rogu pobiera dane od nowa. Aplikacja robi to też sama przy wejściu na odcinek.

### Jak czytać ocenę ryzyka

| Kolor i nazwa | Znaczenie |
| --- | --- |
| Zielony: warunki sprzyjające | Mamy komplet danych i żaden czynnik nie podnosi ryzyka. To nie jest gwarancja bezpieczeństwa. |
| Pomarańczowy: podwyższone ryzyko | Co najmniej jeden czynnik jest niepokojący. Zastanów się, czy masz do tego umiejętności. |
| Czerwony: skrajne warunki | Co najmniej jeden czynnik jest groźny, np. burza lub stan alarmowy wody. |
| Szary: ocena niepełna | Brakuje danych (wody, pogody albo progów) i nic nie wskazuje na zagrożenie. Aplikacja nie daje wtedy zielonego światła. |

Wynikiem jest najgorszy z czynników. Progi, które aplikacja stosuje:

| Czynnik | Podwyższone | Skrajne |
| --- | --- | --- |
| Stan wody | od stanu ostrzegawczego stacji | od stanu alarmowego stacji |
| Temperatura wody | poniżej 10 °C | – |
| Zjawiska lodowe | są zgłoszone | – |
| Porywy wiatru | od 14 m/s (ok. 50 km/h) | od 20 m/s (ok. 72 km/h) |
| Opady za dziś | od 20 mm | od 50 mm |
| Temperatura powietrza | 0 °C i mniej | – |
| Burze | – | prognozowane |
| Przeszkody | zwałka, jaz/próg, niski most, rumosz skalny | – |

Przenoski i inne przeszkody są tylko informacją i nie podnoszą poziomu ryzyka.

### Wodowskaz

Stan wody jest dostępny tylko wtedy, gdy odcinek ma przypisany wodowskaz. Dotknij **Ustaw wodowskaz** (lub **Zmień wodowskaz**) i wpisz nazwę stacji dokładnie tak, jak w danych IMGW, np. „Sromowce Wyżne”. Puste pole wyłącza wodowskaz. Jeśli stacji nie ma w danych, aplikacja poinformuje o tym komunikatem na dole ekranu.

### Przeszkody

1. Dotknij **Zgłoś przeszkodę**, wybierz typ i opisz ją, np. po której stronie rzeki leży drzewo.
2. Zostaw zaznaczone **Dołącz moją pozycję GPS**, jeśli stoisz przy przeszkodzie. Aplikacja zapyta wtedy o zgodę na lokalizację.
3. Przy każdej przeszkodzie możesz dotknąć **Nadal tu jest**, **Już usunięte** albo **Mapa** (otwiera pozycję w aplikacji map).

Przeszkoda znika z listy, gdy ma co najmniej dwa zgłoszenia usunięcia i więcej niż potwierdzeń. Zgłoszenie niezweryfikowane od ponad 30 dni zostaje na liście z ostrzeżeniem, bo zakładamy, że może nadal tam być. Ponieważ nie ma jeszcze kont użytkowników, ta sama osoba może dotknąć przycisku wielokrotnie.

## Spływy: jak używać

Zakładka **Spływy** służy do zaplanowania wyjazdu: kto jedzie, jak się tam dostaną, co zabrać i gdzie kto jest na wodzie. Wszystko zapisuje się na telefonie, na którym je wpisujesz.

### Tworzenie spływu

1. W zakładce **Spływy** dotknij **+**.
2. Wpisz nazwę spływu i swoje imię jako organizatora.
3. Wybierz odcinek z listy albo zostaw „Bez wybranego odcinka”.
4. Ustaw datę i włącz **Nocleg przy rzece**, jeśli spływ trwa dłużej niż dzień.
5. Dotknij **Utwórz**. Otworzy się ekran spływu, a Ty jesteś na liście uczestników.

U góry ekranu spływu widać datę, odcinek i podsumowanie transportu. Kosz w prawym górnym rogu usuwa cały spływ razem z uczestnikami, wyposażeniem i zameldowaniami (po potwierdzeniu).

### Zakładka Uczestnicy

Dotknij **Dodaj uczestnika** i wpisz imię. Pole **Miejsca w aucie** to liczba miejsc w samochodzie tej osoby razem z kierowcą (0 oznacza, że nie jedzie autem). Włącz **Potrzebuje kajaka** dla osób, które wypożyczają sprzęt.

Podsumowanie nad zakładkami porównuje liczbę miejsc w autach z liczbą osób. Na czerwono pojawia się ostrzeżenie, gdy miejsc brakuje albo nikt nie zgłosił auta. Pokazuje też, ilu kajaków trzeba wypożyczyć.

### Zakładka Wyposażenie

- **Dodaj pozycję** wpisuje własną rzecz, np. „namiot 3-osobowy”.
- **Dodaj propozycje** wstawia podstawowy zestaw (kamizelki, rzutka, apteczka, woda i jedzenie, worki wodoszczelne, telefon w etui). Przy spływie z noclegiem dodaje też namiot, śpiwory, karimaty, palnik i latarki. Pozycje, które już masz, nie dublują się.
- Pole wyboru oznacza, że rzecz jest spakowana.
- Przycisk **Bierze: …** przypisuje rzecz osobie z listy uczestników, więc nie zabierzecie dwóch palników.

### Zakładka Pozycje: zameldowanie i prośba o pomoc

1. Dotknij **Zamelduj mnie / poproś o pomoc**.
2. Wybierz osobę z listy albo wpisz imię. Włącz **Potrzebuję pomocy**, jeśli ktoś się zgubił lub ma kłopot.
3. Dotknij **Zamelduj** i zezwól na lokalizację. Aplikacja próbuje ustalić świeżą pozycję przez około 8 sekund, a gdy się nie uda, bierze ostatnią znaną.
4. Zameldowania są na liście od najnowszego. Prośby o pomoc mają czerwone tło. **Pokaż na mapie** otwiera pozycję w aplikacji map.

Jeśli pozycja GPS jest starsza niż 5 minut, przy wpisie pojawia się ostrzeżenie z jej wiekiem. Pozycja jest chwilowa: osoba mogła się już przemieścić.

**Ważne:** ta wersja nie wysyła zameldowań do reszty grupy. Widzisz je tylko na swoim telefonie. W realnym zagrożeniu zadzwoń pod numer alarmowy 112.

## Dokumentacja techniczna

Aplikacja ma cztery warstwy: ekrany Compose, ViewModele, repozytoria i źródła danych (baza Room oraz dwa API). Logika oceny ryzyka leży osobno w pakiecie `domain` i nie zależy od Androida, dzięki czemu da się ją testować bez emulatora. Kod jest w pakiecie `pl.kajakapp`.

&#91;embedded content: architektura aplikacji · 4 warstwy\]

Ekrany wołają tylko ViewModele. ViewModel korzysta z repozytoriów i z domeny, która liczy ryzyko. Wyniki z internetu trafiają najpierw do bazy (cache), a ekran czyta je stamtąd.

### Gdzie jest która funkcja

| Funkcja | Plik (w `pl.kajakapp`) |
| --- | --- |
| Start aplikacji, kontener zależności, wstawienie danych startowych | `KajakApp.kt` |
| Aktywność główna, motyw, pełny ekran | `MainActivity.kt` |
| Nawigacja i dolny pasek zakładek | `ui/AppNavigation.kt` |
| Lista rzek | `ui/RiversScreen.kt` |
| Ekran odcinka (ryzyko, woda, pogoda, przeszkody, wodowskaz) | `ui/SectionScreen.kt` |
| Lista spływów i tworzenie spływu | `ui/TripsScreen.kt` |
| Ekran spływu (uczestnicy, wyposażenie, zameldowania) | `ui/TripDetailScreen.kt` |
| ViewModele i stan wszystkich ekranów | `ui/ViewModels.kt` |
| Baner ryzyka, kolory, wybór z listy, prośba o lokalizację | `ui/Common.kt` |
| Ocena ryzyka (progi i powody) | `domain/RiskAssessor.kt` |
| Reguły świeżości danych (6 godzin) | `domain/DataFreshness.kt` |
| Reguły weryfikacji przeszkód (2 głosy, 30 dni) | `domain/ObstacleRules.kt` |
| Typy: przeszkody, trudności, poziomy ryzyka, odczyty | `domain/Models.kt` |
| Pobieranie wody i pogody, zapis do pamięci podręcznej | `data/ConditionsRepository.kt` |
| Klienci IMGW i Open-Meteo, format odpowiedzi | `data/remote/RemoteApis.kt` |
| Tabele, zapytania SQL i baza | `data/db/Entities.kt`, `Daos.kt`, `AppDatabase.kt` |
| Operacje na rzekach, przeszkodach i spływach | `data/Repositories.kt` |
| Dane startowe i propozycje wyposażenia | `data/SeedData.kt` |
| GPS i otwieranie map | `util/Location.kt` |
| Formatowanie dat i wieku danych | `util/Format.kt` |
| Testy jednostkowe | `app/src/test/.../domain/` |

### Baza danych (Room, plik `kajakapp.db`)

| Tabela | Zawartość |
| --- | --- |
| `rivers` | Rzeki: nazwa, region, typ (nizinna/górska), opis. |
| `sections` | Odcinki rzek: trudność, długość, start, meta, współrzędne, nazwa wodowskazu. |
| `obstacles` | Zgłoszone przeszkody: typ, pozycja, liczba potwierdzeń i głosów za usunięciem. |
| `water_cache`, `weather_cache` | Ostatnie pobrane odczyty wody i pogody dla odcinka, z godziną pobrania. |
| `trips` | Spływy: nazwa, odcinek, data, nocleg, organizator. |
| `participants` | Uczestnicy spływu: imię, miejsca w aucie, potrzeba kajaka. |
| `gear_items` | Wyposażenie: nazwa, kto bierze, czy spakowane. |
| `check_ins` | Zameldowania: osoba, pozycja GPS, czas, prośba o pomoc. |

Usunięcie rzeki usuwa jej odcinki i przeszkody. Usunięcie spływu usuwa jego uczestników, wyposażenie i zameldowania. Tabele `obstacles` i `check_ins` mają pole `pendingSync`, przygotowane pod przyszłą synchronizację z serwerem.

### Co się dzieje po wejściu w odcinek

1. `SectionViewModel` uruchamia odświeżanie (`refresh`).
2. `ConditionsRepository` równolegle pyta IMGW i Open-Meteo. Wodowskaz jest dopasowywany po nazwie stacji, a przy kilku stacjach o tej samej nazwie także po nazwie rzeki.
3. Wyniki zapisują się do `water_cache` i `weather_cache`.
4. Baza wysyła zmianę do ViewModelu, który łączy wodę, pogodę i przeszkody w jeden stan ekranu.
5. Do `RiskAssessor` trafiają tylko dane świeże (do 6 godzin). Wynik pojawia się w banerze.

## Rozwój projektu

### Dodanie rzeki lub odcinka

1. Otwórz `data/SeedData.kt` i dopisz `SeedRiver` z `RiverEntity` oraz listą `SectionEntity`.
2. W polu `stationName` wpisz nazwę stacji dokładnie jak w polu `stacja` w danych IMGW (`https://danepubliczne.imgw.pl/api/data/hydro`). Bez wodowskazu wpisz `null`.
3. Pola `lat` i `lon` to punkt, dla którego pobierana jest pogoda. Wybierz miejsce w środku odcinka.
4. Dane startowe wstawiają się tylko do pustej bazy. Żeby zobaczyć zmianę na telefonie, odinstaluj aplikację albo wyczyść jej dane (**Ustawienia → Aplikacje → KajakApp → Pamięć**).

Trudność i długość odcinka trzeba zweryfikować w przewodniku lub u lokalnego klubu. Aplikacja nie sprawdza ich sama.

### Zmiana progów ryzyka

Progi wiatru, opadów i zimnej wody są stałymi na początku `domain/RiskAssessor.kt`: `GUST_ELEVATED_MS`, `GUST_EXTREME_MS`, `RAIN_ELEVATED_MM`, `RAIN_EXTREME_MM`, `COLD_WATER_C`. Progi stanu wody pochodzą z IMGW i są ustalane osobno dla każdej stacji. Po zmianie uruchom testy, bo opisują obecne wartości.

### Testy

```
./gradlew :app:testDebugUnitTest
```

Testy w `app/src/test/java/pl/kajakapp/domain/` sprawdzają poziomy ryzyka (m.in. że brak danych nigdy nie daje zielonego światła), świeżość danych i reguły przeszkód. Raport powstaje w `app/build/reports/tests/testDebugUnitTest/index.html`. Ekrany i połączenie z API trzeba na razie sprawdzać ręcznie.

### Znane ograniczenia

- Ocena ryzyka nie uwzględnia trudności rzeki ani umiejętności kajakarza.
- Wiatr i opady pochodzą z prognozy na cały dzień, nie na godzinę spływu.
- Pogoda dotyczy jednego punktu odcinka, a stan wody jednego wodowskazu, który może leżeć daleko od trasy.
- Brak kont użytkowników, więc głosy w sprawie przeszkód nie mają limitu na osobę.
- Dane startowe są przykładowe i niezweryfikowane.

### Co dalej

1. Serwer (np. Firebase lub własne API) z synchronizacją przeszkód, spływów i zameldowań. To odblokowuje „szukam ekipy” i widok pozycji całej grupy.
2. Powiadomienia dla grupy o prośbie o pomoc oraz buforowanie pozycji, gdy telefon nie ma zasięgu.
3. Mapy offline z trasą odcinka.
4. Wybór wodowskazu z listy stacji IMGW zamiast wpisywania nazwy.
5. Konta użytkowników i odznaki za weryfikację przeszkód.
