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
    val rivers: List<SeedRiver> = listOf(
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

    val baseGear = listOf(
        "Kamizelki ratunkowe",
        "Rzutka ratunkowa",
        "Apteczka",
        "Woda i jedzenie",
        "Worki wodoszczelne",
        "Telefon w etui wodoszczelnym"
    )

    val overnightGear = listOf(
        "Namiot",
        "Śpiwory",
        "Karimaty",
        "Palnik i gaz",
        "Latarki czołowe"
    )
}
