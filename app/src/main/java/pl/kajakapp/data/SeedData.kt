package pl.kajakapp.data

import pl.kajakapp.data.db.RiverEntity
import pl.kajakapp.data.db.SectionEntity
import pl.kajakapp.domain.Difficulty
import pl.kajakapp.domain.RiverType

/**
 * Przykładowe dane startowe. To punkt wyjścia do rozbudowy bazy rzek, a nie źródło
 * autorytatywnych opisów – trudność i długość odcinków należy zweryfikować
 * z przewodnikami lub lokalnym klubem, zanim ktoś na nich zaufa.
 */
object SeedData {

    class SeedRiver(val river: RiverEntity, val sections: List<SectionEntity>)

    // riverId uzupełniane przy zapisie (SectionEntity.copy(riverId = ...)).
    private val coreRivers: List<SeedRiver> = listOf(
        SeedRiver(
            river = RiverEntity(
                name = "Dunajec",
                region = "Małopolska",
                type = RiverType.MOUNTAIN,
                description = "Rzeka górska; przełom przez Pieniny to popularny odcinek turystyczny."
            ),
            sections = listOf(
                SectionEntity(
                    riverId = 0,
                    name = "Sromowce – Szczawnica (Przełom Pieniński)",
                    lengthKm = 18.0,
                    difficulty = Difficulty.WW1,
                    putIn = "Sromowce",
                    takeOut = "Szczawnica",
                    lat = 49.4,
                    lon = 20.4,
                    stationName = "Sromowce Wyżne",
                    description = "Przykładowy odcinek. Sprawdź lokalne zasady (przystanie, flisacy) i aktualny stan wody.",
                    serverKey = "dunajec-sromowce-szczawnica"
                ),
                SectionEntity(
                    riverId = 0,
                    name = "Szczawnica – Krościenko nad Dunajcem",
                    lengthKm = 6.0,
                    difficulty = Difficulty.WW1,
                    putIn = "Szczawnica",
                    takeOut = "Krościenko nad Dunajcem",
                    lat = 49.44,
                    lon = 20.43,
                    stationName = null,
                    description = "Spokojne zakończenie Pienin, dobry odcinek na krótką wycieczkę. Długość i trudność są orientacyjne – zweryfikuj w przewodniku lub u lokalnego klubu i sprawdź aktualny stan wody.",
                    serverKey = "dunajec-szczawnica-kroscienko"
                ),
                SectionEntity(
                    riverId = 0,
                    name = "Krościenko – Tylmanowa",
                    lengthKm = 13.0,
                    difficulty = Difficulty.WW1,
                    putIn = "Krościenko nad Dunajcem",
                    takeOut = "Tylmanowa",
                    lat = 49.47,
                    lon = 20.46,
                    stationName = null,
                    description = "Szeroka rzeka z kamienistymi progami i szybkim nurtem. Długość i trudność są orientacyjne – zweryfikuj w przewodniku lub u lokalnego klubu i sprawdź aktualny stan wody.",
                    serverKey = "dunajec-kroscienko-tylmanowa"
                ),
                SectionEntity(
                    riverId = 0,
                    name = "Tylmanowa – Łącko",
                    lengthKm = 17.0,
                    difficulty = Difficulty.WW1,
                    putIn = "Tylmanowa",
                    takeOut = "Łącko",
                    lat = 49.52,
                    lon = 20.44,
                    stationName = null,
                    description = "Nurt na szerokim łuku Dunajca, miejscami płycizny i mielizny. Długość i trudność są orientacyjne – zweryfikuj w przewodniku lub u lokalnego klubu i sprawdź aktualny stan wody.",
                    serverKey = "dunajec-tylmanowa-lacko"
                ),
                SectionEntity(
                    riverId = 0,
                    name = "Łącko – Nowy Sącz",
                    lengthKm = 35.0,
                    difficulty = Difficulty.WW1,
                    putIn = "Łącko",
                    takeOut = "Nowy Sącz",
                    lat = 49.58,
                    lon = 20.55,
                    stationName = null,
                    description = "Dłuższy odcinek; w Nowym Sączu uważaj na jazy i mosty. Długość i trudność są orientacyjne – zweryfikuj w przewodniku lub u lokalnego klubu i sprawdź aktualny stan wody.",
                    serverKey = "dunajec-lacko-nowy-sacz"
                )
            )
        ),
        SeedRiver(
            river = RiverEntity(
                name = "Krutynia",
                region = "Warmia i Mazury",
                type = RiverType.LOWLAND,
                description = "Nizinny szlak kajakowy przez Puszczę Piską."
            ),
            sections = listOf(
                SectionEntity(
                    riverId = 0,
                    name = "Sorkwity – Ukta",
                    lengthKm = 50.0,
                    difficulty = Difficulty.FLAT,
                    putIn = "Sorkwity",
                    takeOut = "Ukta",
                    lat = 53.75,
                    lon = 21.4,
                    stationName = null,
                    description = "Przykładowy odcinek bez przypisanego wodowskazu – możesz go ustawić na ekranie odcinka.",
                    serverKey = "krutynia-sorkwity-ukta"
                ),
                SectionEntity(
                    riverId = 0,
                    name = "Sorkwity – Zgon",
                    lengthKm = 22.0,
                    difficulty = Difficulty.FLAT,
                    putIn = "Sorkwity",
                    takeOut = "Zgon",
                    lat = 53.78,
                    lon = 21.3,
                    stationName = null,
                    description = "Początek szlaku, jeziora i spokojna rzeka. Długość i trudność są orientacyjne – zweryfikuj w przewodniku lub u lokalnego klubu i sprawdź aktualny stan wody.",
                    serverKey = "krutynia-sorkwity-zgon"
                ),
                SectionEntity(
                    riverId = 0,
                    name = "Zgon – Krutyń",
                    lengthKm = 13.0,
                    difficulty = Difficulty.FLAT,
                    putIn = "Zgon",
                    takeOut = "Krutyń",
                    lat = 53.75,
                    lon = 21.5,
                    stationName = null,
                    description = "Najpiękniejszy fragment w lesie, wąskie zakola i dużo zwałek. Długość i trudność są orientacyjne – zweryfikuj w przewodniku lub u lokalnego klubu i sprawdź aktualny stan wody.",
                    serverKey = "krutynia-zgon-krutyn"
                ),
                SectionEntity(
                    riverId = 0,
                    name = "Krutyń – Ukta",
                    lengthKm = 9.0,
                    difficulty = Difficulty.FLAT,
                    putIn = "Krutyń",
                    takeOut = "Ukta",
                    lat = 53.72,
                    lon = 21.62,
                    stationName = null,
                    description = "Końcowy odcinek przed Uktą, miejscami płytko. Długość i trudność są orientacyjne – zweryfikuj w przewodniku lub u lokalnego klubu i sprawdź aktualny stan wody.",
                    serverKey = "krutynia-krutyn-ukta"
                )
            )
        )
    )

    private fun sec(river: String, name: String, putIn: String, takeOut: String, km: Double, diff: Difficulty, lat: Double, lon: Double) =
        SectionEntity(
            riverId = 0, name = name, lengthKm = km, difficulty = diff, putIn = putIn, takeOut = takeOut, lat = lat, lon = lon,
            stationName = null,
            description = "Orientacyjny odcinek: długość, trudność i miejsca wodowania zweryfikuj w przewodniku lub u lokalnego klubu oraz sprawdź aktualny stan wody. Wodowskaz jest dobierany automatycznie.",
            serverKey = river + "-" + slug(putIn) + "-" + slug(takeOut)
        )

    private fun slug(text: String): String =
        text.lowercase().map { c ->
            when (c) { 'ą' -> 'a'; 'ć' -> 'c'; 'ę' -> 'e'; 'ł' -> 'l'; 'ń' -> 'n'; 'ó' -> 'o'; 'ś' -> 's'; 'ź', 'ż' -> 'z'; else -> c }
        }.joinToString("").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Kolejne rzeki. Klucz odcinka zaczyna się od identyfikatora rzeki (tak serwer rozpoznaje „tę samą rzekę”). */
    private val moreRivers: List<SeedRiver> = listOf(
        SeedRiver(
            river = RiverEntity(name = "Wda", region = "Pomorskie / Kujawsko-Pomorskie", type = RiverType.LOWLAND, description = "Czysta rzeka w Borach Tucholskich, popularna wśród kajakarzy."),
            sections = listOf(
                sec("wda", "Tleń – Osie", "Tleń", "Osie", 22.0, Difficulty.WW1, 53.57, 18.2),
                sec("wda", "Osie – Świecie", "Osie", "Świecie", 38.0, Difficulty.FLAT, 53.56, 18.33)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Drawa", region = "Zachodniopomorskie", type = RiverType.LOWLAND, description = "Szybka, kręta rzeka przez Drawieński Park Narodowy."),
            sections = listOf(
                sec("drawa", "Drawno – Stare Osieczno", "Drawno", "Stare Osieczno", 35.0, Difficulty.WW1, 53.21, 15.74),
                sec("drawa", "Stare Osieczno – Krzyż Wielkopolski", "Stare Osieczno", "Krzyż Wielkopolski", 45.0, Difficulty.FLAT, 53.0, 15.9)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Brda", region = "Pomorskie / Kujawsko-Pomorskie", type = RiverType.LOWLAND, description = "Klasyczny szlak przez Bory Tucholskie, jeziora i niewielkie progi."),
            sections = listOf(
                sec("brda", "Charzykowy – Męcikał", "Charzykowy", "Męcikał", 40.0, Difficulty.FLAT, 53.77, 17.52),
                sec("brda", "Tuchola – Koronowo", "Tuchola", "Koronowo", 50.0, Difficulty.WW1, 53.59, 17.86)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Wieprza", region = "Zachodniopomorskie", type = RiverType.LOWLAND, description = "Przyjemna rzeka nizinna z szybszym nurtem na przełomach."),
            sections = listOf(
                sec("wieprza", "Sławno – Darłowo", "Sławno", "Darłowo", 40.0, Difficulty.FLAT, 54.36, 16.68)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Słupia", region = "Pomorskie", type = RiverType.LOWLAND, description = "Szybka rzeka z licznymi jazami i elektrowniami wodnymi."),
            sections = listOf(
                sec("slupia", "Soszyca – Słupsk", "Soszyca", "Słupsk", 35.0, Difficulty.WW1, 54.33, 17.07),
                sec("slupia", "Słupsk – Ustka", "Słupsk", "Ustka", 20.0, Difficulty.FLAT, 54.46, 17.03)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Parsęta", region = "Zachodniopomorskie", type = RiverType.LOWLAND, description = "Żwirowe dno, dość szybki nurt, popularna wśród kajakarzy i wędkarzy."),
            sections = listOf(
                sec("parseta", "Połczyn-Zdrój – Białogard", "Połczyn-Zdrój", "Białogard", 55.0, Difficulty.WW1, 53.77, 16.09),
                sec("parseta", "Białogard – Kołobrzeg", "Białogard", "Kołobrzeg", 40.0, Difficulty.FLAT, 54.0, 15.99)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Rega", region = "Zachodniopomorskie", type = RiverType.LOWLAND, description = "Niewielka rzeka wpadająca do Bałtyku, wiele zwałek po wezbraniach."),
            sections = listOf(
                sec("rega", "Resko – Trzebiatów", "Resko", "Trzebiatów", 45.0, Difficulty.WW1, 53.77, 15.4)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Gwda", region = "Wielkopolskie / Zachodniopomorskie", type = RiverType.LOWLAND, description = "Rzeka przez Pojezierze Wałeckie; szybki nurt i progi."),
            sections = listOf(
                sec("gwda", "Jastrowie – Piła", "Jastrowie", "Piła", 50.0, Difficulty.WW1, 53.42, 16.82),
                sec("gwda", "Piła – Ujście", "Piła", "Ujście", 40.0, Difficulty.FLAT, 53.15, 16.74)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Pilica", region = "Łódzkie / Mazowieckie", type = RiverType.LOWLAND, description = "Jedna z najpopularniejszych rzek nizinnych w Polsce; piaszczyste mielizny."),
            sections = listOf(
                sec("pilica", "Przedbórz – Sulejów", "Przedbórz", "Sulejów", 55.0, Difficulty.WW1, 51.09, 19.88),
                sec("pilica", "Tomaszów Mazowiecki – Inowłódz", "Tomaszów Mazowiecki", "Inowłódz", 25.0, Difficulty.FLAT, 51.47, 20.01),
                sec("pilica", "Inowłódz – Nowe Miasto nad Pilicą", "Inowłódz", "Nowe Miasto nad Pilicą", 45.0, Difficulty.FLAT, 51.5, 20.14)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Czarna Hańcza", region = "Podlaskie", type = RiverType.LOWLAND, description = "Rzeka przez Suwalszczyznę i Puszczę Augustowską, szlak do Kanału Augustowskiego."),
            sections = listOf(
                sec("hancza", "Stary Folwark – Augustów", "Stary Folwark", "Augustów", 45.0, Difficulty.FLAT, 54.01, 23.05)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Biebrza", region = "Podlaskie", type = RiverType.LOWLAND, description = "Dzika rzeka bagienna w Biebrzańskim Parku Narodowym; liczne meandry."),
            sections = listOf(
                sec("biebrza", "Osowiec – Goniądz", "Osowiec-Twierdza", "Goniądz", 50.0, Difficulty.FLAT, 53.46, 22.65),
                sec("biebrza", "Goniądz – Wizna", "Goniądz", "Wizna", 75.0, Difficulty.FLAT, 53.5, 22.73)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Narew", region = "Podlaskie / Mazowieckie", type = RiverType.LOWLAND, description = "Szeroka nizinna rzeka z rozlewiskami; fragmenty o bardzo wolnym nurcie."),
            sections = listOf(
                sec("narew", "Suraż – Tykocin", "Suraż", "Tykocin", 45.0, Difficulty.FLAT, 52.95, 22.96),
                sec("narew", "Pułtusk – Serock", "Pułtusk", "Serock", 35.0, Difficulty.FLAT, 52.7, 21.09)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Bug", region = "Podlaskie / Mazowieckie", type = RiverType.LOWLAND, description = "Naturalna rzeka graniczna z licznymi łachami i zakolami."),
            sections = listOf(
                sec("bug", "Drohiczyn – Brok", "Drohiczyn", "Brok", 60.0, Difficulty.FLAT, 52.4, 22.66)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Wkra", region = "Mazowieckie", type = RiverType.LOWLAND, description = "Spokojna, miejscami zarośnięta rzeka z licznymi zwałkami."),
            sections = listOf(
                sec("wkra", "Płońsk – Nowy Dwór Mazowiecki", "Płońsk", "Nowy Dwór Mazowiecki", 80.0, Difficulty.FLAT, 52.63, 20.38)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Wisła", region = "Małopolskie / Świętokrzyskie", type = RiverType.LOWLAND, description = "Największa rzeka Polski; uwaga na statki, jazy i zmienne stany wody."),
            sections = listOf(
                sec("wisla", "Kraków – Niepołomice", "Kraków", "Niepołomice", 35.0, Difficulty.FLAT, 50.05, 19.93),
                sec("wisla", "Sandomierz – Annopol", "Sandomierz", "Annopol", 55.0, Difficulty.FLAT, 50.68, 21.75)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "San", region = "Podkarpackie", type = RiverType.MOUNTAIN, description = "Duża rzeka podgórska; w górnym biegu szybki nurt i płycizny."),
            sections = listOf(
                sec("san", "Sanok – Dynów", "Sanok", "Dynów", 45.0, Difficulty.WW1, 49.56, 22.2),
                sec("san", "Krasiczyn – Przemyśl", "Krasiczyn", "Przemyśl", 20.0, Difficulty.FLAT, 49.8, 22.66)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Poprad", region = "Małopolskie", type = RiverType.MOUNTAIN, description = "Górska rzeka Beskidu Sądeckiego; szybki nurt, kamieniste progi."),
            sections = listOf(
                sec("poprad", "Piwniczna-Zdrój – Stary Sącz", "Piwniczna-Zdrój", "Stary Sącz", 30.0, Difficulty.WW2, 49.44, 20.72)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Nysa Kłodzka", region = "Dolnośląskie", type = RiverType.MOUNTAIN, description = "Sudecka rzeka z progami i jazami; stany wody szybko się zmieniają."),
            sections = listOf(
                sec("nysa", "Kłodzko – Bardo", "Kłodzko", "Bardo", 14.0, Difficulty.WW2, 50.43, 16.65),
                sec("nysa", "Bardo – Paczków", "Bardo", "Paczków", 45.0, Difficulty.WW1, 50.5, 16.88)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Warta", region = "Łódzkie / Wielkopolskie", type = RiverType.LOWLAND, description = "Długa rzeka nizinna; szerokie zakola, miejscami jazy i piaszczyste łachy."),
            sections = listOf(
                sec("warta", "Sieradz – Uniejów", "Sieradz", "Uniejów", 65.0, Difficulty.FLAT, 51.6, 18.73),
                sec("warta", "Uniejów – Koło", "Uniejów", "Koło", 45.0, Difficulty.FLAT, 51.97, 18.8)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Noteć", region = "Kujawsko-Pomorskie / Wielkopolskie", type = RiverType.LOWLAND, description = "Spokojna rzeka przez Pradolinę Toruńsko-Eberswaldzką."),
            sections = listOf(
                sec("notec", "Nakło nad Notecią – Czarnków", "Nakło nad Notecią", "Czarnków", 85.0, Difficulty.FLAT, 53.14, 17.6)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Mała Panew", region = "Opolskie", type = RiverType.LOWLAND, description = "Meandrująca rzeka przez Lasy Stobrawskie; liczne zwałki."),
            sections = listOf(
                sec("malapanew", "Kolonowskie – Ozimek", "Kolonowskie", "Ozimek", 45.0, Difficulty.WW1, 50.69, 18.37)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Odra", region = "Opolskie / Dolnośląskie", type = RiverType.LOWLAND, description = "Duża rzeka żeglowna; uważaj na barki, jazy i śluzy."),
            sections = listOf(
                sec("odra", "Koźle – Opole", "Koźle", "Opole", 65.0, Difficulty.FLAT, 50.34, 18.15),
                sec("odra", "Brzeg – Oława", "Brzeg", "Oława", 35.0, Difficulty.FLAT, 50.86, 17.47)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Drwęca", region = "Warmińsko-Mazurskie / Kujawsko-Pomorskie", type = RiverType.LOWLAND, description = "Szlak o charakterze niemal górskim w górnym biegu; bystrza i kamienie."),
            sections = listOf(
                sec("dreweca", "Nowe Miasto Lubawskie – Brodnica", "Nowe Miasto Lubawskie", "Brodnica", 60.0, Difficulty.WW1, 53.42, 19.59),
                sec("dreweca", "Brodnica – Golub-Dobrzyń", "Brodnica", "Golub-Dobrzyń", 30.0, Difficulty.FLAT, 53.26, 19.4)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Wierzyca", region = "Pomorskie", type = RiverType.LOWLAND, description = "Kręta rzeka o szybkim nurcie, popularna na kajaki weekendowe."),
            sections = listOf(
                sec("wierzyca", "Starogard Gdański – Pelplin", "Starogard Gdański", "Pelplin", 35.0, Difficulty.WW1, 53.96, 18.53),
                sec("wierzyca", "Pelplin – Gniew", "Pelplin", "Gniew", 25.0, Difficulty.FLAT, 53.93, 18.7)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Biała Tarnowska", region = "Małopolskie", type = RiverType.MOUNTAIN, description = "Podgórska rzeka Pogórza, gwałtownie reaguje na opady."),
            sections = listOf(
                sec("bialatarnowska", "Grybów – Tarnów", "Grybów", "Tarnów", 60.0, Difficulty.WW1, 49.62, 20.95)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Raba", region = "Małopolskie", type = RiverType.MOUNTAIN, description = "Rzeka Beskidów i Pogórza; szybki nurt, jazy."),
            sections = listOf(
                sec("raba", "Dobczyce – Gdów", "Dobczyce", "Gdów", 20.0, Difficulty.WW1, 49.88, 20.88)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Soła", region = "Śląskie / Małopolskie", type = RiverType.MOUNTAIN, description = "Beskidzka rzeka; w górnym biegu bystra, niżej kontrolowana zaporami."),
            sections = listOf(
                sec("sola", "Żywiec – Kobiernice", "Żywiec", "Kobiernice", 30.0, Difficulty.WW1, 49.68, 19.2)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Łyna", region = "Warmińsko-Mazurskie", type = RiverType.LOWLAND, description = "Malowniczy szlak przez Warmię; liczne zakola i zwałki."),
            sections = listOf(
                sec("lyna", "Olsztyn – Dobre Miasto", "Olsztyn", "Dobre Miasto", 35.0, Difficulty.FLAT, 53.78, 20.49)
            )
        ),
        SeedRiver(
            river = RiverEntity(name = "Czarna Przemsza", region = "Śląskie / Małopolskie", type = RiverType.LOWLAND, description = "Niewielka rzeka Jury i Zagłębia, krótkie odcinki o szybkim nurcie."),
            sections = listOf(
                sec("czarnaprzemsza", "Podwilk – Jaworzno", "Podwilk", "Jaworzno", 25.0, Difficulty.WW1, 50.4, 19.4)
            )
        )
    )

    val rivers: List<SeedRiver> = coreRivers + moreRivers

    val baseGear = listOf(
        "Kamizelki ratunkowe",
        "Rzutka ratunkowa",
        "Apteczka",
        "Woda i jedzenie",
        "Worki wodoszczelne",
        "Telefon w etui wodoszczelnym"
    )

    /** Pozycje, które przy wyborze wyposażenia są domyślnie oznaczone jako wymagane. */
    val requiredGearByDefault = setOf("Kamizelki ratunkowe")

    val overnightGear = listOf(
        "Namiot",
        "Śpiwory",
        "Karimaty",
        "Palnik i gaz",
        "Latarki czołowe"
    )
}
