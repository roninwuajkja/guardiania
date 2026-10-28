package cl.guardiania.agent.capture

import java.text.Normalizer
import java.util.Locale

/**
 * Converts one notification into a package category plus local rule flags.
 * The raw title/text is read in memory and discarded: it is never stored,
 * logged or sent. A missing flag does not mean the message is safe; it only
 * means no local lexical rule matched.
 */
object Sanitizer {

    data class Result(val category: String, val flags: List<String>)

    private data class Rule(val code: String, val pattern: Regex)

    private val rules = listOf(
        Rule(
            "self_harm",
            Regex("\\b(quiero (morir|desaparecer)|quitarme la vida|me quiero matar|matarme|autolesion\\w*|cortarme|suicid\\w*)\\b"),
        ),
        Rule(
            "grooming",
            Regex("\\b(manda(me)? (una )?foto|no le (digas|cuentes) a (nadie|tus papas|tu mama|tu papa)|nos vemos a solas|eres (muy )?madur\\w+|guardemos el secreto|secreto entre (nosotros|tu y yo))\\b"),
        ),
        Rule(
            "sexual_content",
            Regex("\\b(porno\\w*|sexo|desnud\\w*|fotos? (intimas?|hot)|pack de fotos)\\b"),
        ),
        Rule(
            "violence",
            Regex("\\b(te voy a (pegar|matar)|voy a golpearte|golpiza|amenaza\\w*|machete)\\b"),
        ),
        Rule(
            "bullying",
            Regex("\\b(nadie te quiere|no te juntes con|te van a pegar|acos\\w*|bullying|molest\\w*|eres un (perdedor|raro))\\b"),
        ),
        Rule(
            "drugs",
            Regex("\\b(marihuana|pasto|droga\\w*|cocaina|alcohol|cerveza|vape\\w*|tabaco)\\b"),
        ),
        Rule(
            "gambling",
            Regex("\\b(apuesta\\w*|casino|ruleta|tragamonedas|loteria|bet)\\b"),
        ),
    )

    private val packageCategories = listOf(
        "com.whatsapp" to "mensajeria",
        "org.telegram" to "mensajeria",
        "com.discord" to "mensajeria",
        "com.instagram" to "social",
        "com.snapchat" to "social",
        "com.facebook" to "social",
        "com.zhiliaoapp.musically" to "social",
        "com.google.android.youtube" to "video",
        "com.netflix" to "video",
        "com.twitch" to "video",
        "com.roblox" to "juegos",
        "com.mojang" to "juegos",
        "com.supercell" to "juegos",
        "com.android.chrome" to "navegacion",
        "com.brave" to "navegacion",
        "org.mozilla" to "navegacion",
        "com.duolingo" to "educacion",
        "com.google.android.apps.docs" to "educacion",
    )

    fun categoryFor(packageName: String): String {
        return packageCategories.firstOrNull { packageName.startsWith(it.first) }?.second ?: "otras"
    }

    fun sanitize(packageName: String, title: String?, text: String?): Result {
        val content = normalize(listOfNotNull(title, text).joinToString(" ").take(MAX_SCANNED_CHARS))
        val flags = rules
            .asSequence()
            .filter { it.pattern.containsMatchIn(content) }
            .map { it.code }
            .distinct()
            .take(MAX_FLAGS)
            .toList()
        return Result(categoryFor(packageName), flags)
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed.replace(diacritics, "").lowercase(Locale.ROOT)
    }

    private val diacritics = Regex("\\p{Mn}+")
    private const val MAX_SCANNED_CHARS = 4000
    private const val MAX_FLAGS = 8
}
