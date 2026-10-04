package pl.kajakapp.domain

/**
 * Zasady listy wyposażenia spływu. Organizator wybiera pozycje i oznacza je jako wymagane albo
 * zalecane; każdy uczestnik potwierdza, że dany element ma – zawsze tylko za siebie.
 */
object GearRules {
    const val REQUIRED = "required"
    const val RECOMMENDED = "recommended"

    /** Nazwa „uczestnika” w spływie zapisanym tylko na telefonie, gdy nikt nie jest zalogowany. */
    const val LOCAL_VIEWER = "Ja"

    private const val SEPARATOR = "\n"

    fun normalizeRequirement(value: String?): String = if (value == REQUIRED) REQUIRED else RECOMMENDED

    fun requirementLabel(value: String): String =
        if (normalizeRequirement(value) == REQUIRED) "Wymagane" else "Zalecane"

    /** Lista osób, które potwierdziły pozycję (zapisana w bazie jako jeden tekst). */
    fun decode(raw: String): List<String> = raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    fun encode(names: Collection<String>): String {
        val unique = ArrayList<String>()
        for (name in names) {
            val clean = name.trim()
            if (clean.isNotEmpty() && unique.none { it.equals(clean, ignoreCase = true) }) unique += clean
        }
        return unique.joinToString(SEPARATOR)
    }

    fun isConfirmed(raw: String, viewer: String): Boolean =
        decode(raw).any { it.equals(viewer, ignoreCase = true) }

    /** Zwraca zapis listy po dodaniu albo cofnięciu potwierdzenia przez [viewer]. */
    fun withConfirmation(raw: String, viewer: String, confirmed: Boolean): String {
        val others = decode(raw).filterNot { it.equals(viewer, ignoreCase = true) }
        return encode(if (confirmed) others + viewer else others)
    }
}
