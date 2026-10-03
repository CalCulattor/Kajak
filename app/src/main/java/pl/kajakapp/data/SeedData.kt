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
