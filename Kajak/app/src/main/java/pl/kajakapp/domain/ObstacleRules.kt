package pl.kajakapp.domain

/**
 * Zasady weryfikacji zgłoszeń przeszkód przez społeczność.
 * Przeszkoda znika dopiero, gdy co najmniej [REMOVAL_VOTES_NEEDED] osoby zgłoszą
 * usunięcie i jest to więcej głosów niż potwierdzeń. Przeterminowane zgłoszenia nadal
 * liczą się jako aktywne (bezpieczniej), tylko są oznaczane jako niezweryfikowane.
 */
object ObstacleRules {
    const val REMOVAL_VOTES_NEEDED = 2
    const val STALE_AFTER_DAYS = 30L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun isActive(confirmations: Int, removalVotes: Int): Boolean =
        !(removalVotes >= REMOVAL_VOTES_NEEDED && removalVotes > confirmations)

    fun isStale(lastVerifiedAt: Long, now: Long): Boolean =
        now - lastVerifiedAt > STALE_AFTER_DAYS * DAY_MS
}
