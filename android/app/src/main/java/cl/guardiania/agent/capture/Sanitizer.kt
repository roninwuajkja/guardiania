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
            Regex("\\b(quiero (morir|desaparecer)|quitarme la vida|me quiero matar|matarme|autolesion\\w*|cortarme|suicid\\w*|mejor (no estar|morir)|no quiero (vivir|seguir viviendo))\\b"),
        ),
        Rule(
            "grooming",
            Regex("\\b(manda(me)? (una )?foto|no le (digas|cuentes) a (nadie|tus papas|tus padres|tu mama|tu papa)|nos vemos a solas|eres (muy )?madur\\w+|guardemos el secreto|secreto entre (nosotros|tu y yo)|borra (el chat|los mensajes)|que edad tienes)\\b"),
        ),
        Rule(
            "sexual_content",
            Regex("\\b(porno\\w*|sexo|desnud\\w*|fotos? (intimas?|hot)|pack de fotos)\\b"),
        ),
        Rule(
            "violence",
            Regex("\\b(te voy a (pegar|matar|sacar la cresta)|voy a golpearte|golpiza|amenaza\\w*|machete|cuchillo|pelea)\\b"),
        ),
        Rule(
            "coercion",
            Regex("\\b(me obliga\\w*|me amenaza\\w*|si no (me )?(mandas|haces|respondes)|te voy a funar|si le (cuentas|dices) (a alguien|a tus papas)|chantaje\\w*)\\b"),
        ),
        Rule(
            "bullying",
            Regex("\\b(nadie te quiere|todos te odian|no te juntes con|te van a pegar|acos\\w*|bullying|molest\\w*|webe\\w*|funar|eres un (perdedor|raro))\\b"),
        ),
        Rule(
            "drugs",
            Regex("\\b(marihuana|pasto|droga\\w*|cocaina|alcohol|cerveza|vape\\w*|tabaco|tussi|extasis|lsd)\\b"),
        ),
        Rule(
            "gambling",
            Regex("\\b(apuesta\\w*|casino|ruleta|tragamonedas|loteria|bet|cuotas deportivas)\\b"),
        ),
        Rule(
            "malestar",
            Regex("\\b(no aguanto (mas|nada)|me siento (solo|sola|mal|triste|vacio|vacia)|estoy (cansad\\w+|chata\\w*) de todo|no quiero ir (al colegio|a clases)|me da miedo|nadie me (quiere|habla))\\b"),
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
