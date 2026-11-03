package cl.guardiania.agent.capture

import java.text.Normalizer
import java.util.Locale

/**
 * Converts one notification into a package category plus local rule flags.
 * The raw title/text is read in memory and discarded: it is never stored,
 * logged or sent. A missing flag does not mean the message is safe; it only
 * means no local lexical rule matched.
 *
 * The rules are ordered from most to least urgent so that, when a message
 * matches many patterns, the eight flags kept for the vector are the ones that
 * matter most. Each regex works on normalized text (lowercase, no accents).
 */
object Sanitizer {

    data class Result(val category: String, val flags: List<String>)

    private data class Rule(val code: String, val pattern: Regex)

    private val rules = listOf(
        Rule(
            "self_harm",
            Regex(
                """\b(quiero (morir|desaparecer)|me quiero (morir|matar)|quitarme la vida|me quiero quitar la vida|autolesion\w*|me quiero cortar|quiero cortarme|cortarme (las|los) (brazos|piernas|venas|munecas)|suicid\w*|mejor (no estar|morir)|no quiero (vivir|seguir viviendo)|quiero desaparecer|no quiero despertar (nunca|mas)|me quiero (ahorcar|colgar)|saltar (de|desde) (un|el) (edificio|puente)|carta de despedida|me despido (para siempre|de todos)|esta es mi ultima (carta|noche))\b""",
            ),
        ),
        Rule(
            "grooming",
            Regex(
                """\b(manda(me)? (una )?(foto|video)|mandame (fotos|videos)|no le (digas|cuentes) a (nadie|tus papas|tus padres|tu mama|tu papa)|nos vemos a solas|eres (muy )?madur\w+|guardemos (el|nuestro) secreto|secreto entre (nosotros|tu y yo)|borra (el chat|los mensajes|la conversacion)|no se lo cuentes a nadie|te doy (dinero|regalos) (si|a cambio)|(compra|pideme|enviame) (una )?tarjeta de regalo)\b""",
            ),
        ),
        Rule(
            "sextortion",
            Regex(
                """\b(tengo (tus|las) (fotos|imagenes|videos)|voy a (publicar|filtrar|exponer|enviar) (tus|las) (fotos|imagenes|videos)|si no (me )?(pagas|mandas (dinero|plata)|transfieres)|te voy a (funar|exponer|filtrar)|chantaje (sexual|con fotos)|sextorsion\w*|fotos (intimas|privadas) (a cambio|o publico))\b""",
            ),
        ),
        Rule(
            "abuse",
            Regex(
                """\b(me (violo|violaron|manoseo|manosearon)|abuso (sexual|infantil)|(mi|el|la) (papa|padre|mama|madre|padrastro|madrastra|tio|tia|abuelo|abuela|hermano|hermana|vecino|profesor|entrenador|pololo|polola|pareja|novio|novia|ex) me (violo|manoseo|pega|golpea|maltrata|obligo a|toco (las|los|mis) (partes|pecho|piernas|genitales|culo))|me obliga a (desnudarme|tocarlo|tocarla)|me encierra (en|sin)|no me deja salir de (la casa|mi pieza))\b""",
            ),
        ),
        Rule(
            "trafficking",
            Regex(
                """\b(trata de (personas|blancas)|te van a (vender|llevar) (a otro pais|para trabajar)|prostitucion (infantil|juvenil)|servicios sexuales|cliente (mayor|adulto) (paga|quiere)|te obligan a (prostituirte|trabajar en la calle)|explotacion (sexual|laboral))\b""",
            ),
        ),
        Rule(
            "radicalization",
            Regex(
                """\b(reclutar (para|a) (la yihad|el estado islamico|grupo armado)|unete (a la|al) (yihad|califato|estado islamico|al qaeda)|apoya (a|al) (estado islamico|al qaeda|yihad)|supremacia (blanca|aria)|raza superior|limpieza (etnica|social)|muerte a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas)|guerra santa contra|reclutamiento (extremista|terrorista))\b""",
            ),
        ),
        Rule(
            "sexual_content",
            Regex(
                """\b(porno\w*|sexo|desnud\w*|fotos? (intimas?|hot|sin ropa)|pack de fotos|nudes?|onlyfans|xvideos|pornhub|video (sexual|intimo)|contenido (sexual|adulto)|menor (desnuda|desnudo))\b""",
            ),
        ),
        Rule(
            "violence",
            Regex(
                """\b(te voy a (pegar|matar|sacar la cresta|moler|patear)|voy a golpearte|golpiza|(me|te|nos) amenaza\w*|amenaza (de muerte|con (matar|pegar|arma|un arma))|machete|cuchillo|pelea|apunalar|punalar|tiroteo|bomba|atentado|quiero matar a|voy a matar a|me (pega|golpea) (en el colegio|todos los dias|sin razon))\b""",
            ),
        ),
        Rule(
            "weapons",
            Regex(
                """\b(pistola|revolver|escopeta|rifle|arma de fuego|tengo (un|una) arma|tengo (una|mi) pistola|balas|municiones|machete|navaja|punal|granada|explosivo|bomba casera|silenciador|fusil)\b""",
            ),
        ),
        Rule(
            "coercion",
            Regex(
                """\b(me obliga\w*|me amenaza\w*|si no (me )?(mandas|haces|respondes|pagas|vienes)|te voy a funar|si le (cuentas|dices) (a alguien|a tus papas)|chantaje\w*|me esta obligando|me esta amenazando|si no haces lo que digo|vas a ver lo que pasa|te arrepentiras|no te voy a dejar en paz)\b""",
            ),
        ),
        Rule(
            "bullying",
            Regex(
                """\b(nadie te (quiere|habla)|todos te odian|no tienes amigos|no te juntes con|te van a (pegar|funar|sacar)|te vamos a (pegar|funar|sacar)|acos\w*|bullying|molest\w*|webe\w*|funar|eres un (perdedor|raro|fracasado)|no sirves para nada|me (pega|golpea) (en el colegio|todos los dias))\b""",
            ),
        ),
        Rule(
            "hate_speech",
            Regex(
                """\b(no te juntes con (negros|indios|venecos|sudacas|maricones|musulmanes|judios)|esos (negros|indios|venecos|sudacas|maricones|musulmanes|judios) (no deberian|tienen que)|muerte a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas)|matar a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas)|limpieza (etnica|social)|supremacia (blanca|aria)|raza superior|odio a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas))\b""",
            ),
        ),
        Rule(
            "insult_mild",
            Regex(
                """\b(tonto|tonta|tontito|tontita|bobo|boba|ridiculo|ridicula|inutil|mediocre|lerdo|lerda|papanatas|zoquete|menso|mensa|blandengue|(eres|es|son|que) (un|una|unos|unas)? (payaso|payasa|pavo|pava|pesado|pesada|cargante|novato|novata|chistoso|chistosa|flojo|floja))\b""",
            ),
        ),
        Rule(
            "insult_moderate",
            Regex(
                """\b(idiota|imbecil|estupido|estupida|tarado|tarada|pelotudo|pelotuda|aweonao|aweonada|sacowea|saco de weas|huevon de mierda|weon de mierda|(eres|es|son|que) (un|una|unos|unas)? (huevon|huevona|weon|weona|gil|gila|cerdo|cerda|rata|asqueroso|asquerosa|patetico|patetica|arrastrado|arrastrada|chupamedias))\b""",
            ),
        ),
        Rule(
            "insult_severe",
            Regex(
                """\b(hijo de puta|hija de puta|hijos de puta|conchetumadre|conchetumare|culiao|culiada|maricon|maricona|maricon de mierda|(eres|es|son|que) (un|una|unos|unas)? (puto|puta|perra|zorra|fleto|fleta)|perra de mierda|zorra de mierda|chupapico|chupapija|malparido|malparida|bastardo|bastarda|escoria|basura humana|retrasado|retrasada|mongolico|mongolica|sudaca|veneco|indio de mierda|negro de mierda|ojala te mueras|ojala te maten|muerete (de verdad|luego|ahora)|te voy a matar)\b""",
            ),
        ),
        Rule(
            "eating_disorder",
            Regex(
                """\b(no (he )?comido (en|hace|todo el dia)|deje de comer|vomito? (despues de comer|lo que como)|me provoco el vomito|purga(s)?|laxante\w*|ayuno (extremo|prolongado|de (varios|muchos) dias)|atracon\w*|trastorno (alimentario|de la conducta alimentaria)|anorexia|bulimia|ortorexia)\b""",
            ),
        ),
        Rule(
            "doxxing",
            Regex(
                """\b(voy a (publicar|filtrar|exponer|doxxear) (tu|tus|su|sus) (direccion|numero|rut|colegio|fotos|datos|nombre)|tengo (tu|tus) (direccion|numero|rut|colegio|fotos|datos)|se donde (vives|estudias|vas al colegio)|publicare (tu|tus) (direccion|numero|rut|fotos)|doxxear\w*|doxxing|exponer (tu|tus) (datos|fotos|direccion))\b""",
            ),
        ),
        Rule(
            "runaway",
            Regex(
                """\b(me voy a (arrancar|escapar|ir de casa|fugar)|quiero (arrancar|escapar|irme de casa)|no vuelvo (a casa|mas)|me voy de (la casa|mi casa)|huir de (casa|mi casa)|no quiero volver a casa)\b""",
            ),
        ),
        Rule(
            "dangerous_challenge",
            Regex(
                """\b(ballena azul|reto (de la )?(ballena|asfixia|blackout|48 horas|desaparecer)|blackout challenge|reto viral (peligroso|mortal)|juego de la asfixia|reto de la (muerte|sangre)|48 horas desaparecido|desaparecer por 48|salto mortal|momo challenge|reto de momo|reto de saltar (de|desde))\b""",
            ),
        ),
        Rule(
            "drugs",
            Regex(
                """\b(marihuana|(fumar|fumando|comprar|vender) pasto|droga\w*|cocaina|merca|falopa|pasta base|porro|tussi|extasis|lsd|ketamina|mdma|anfetamina|metanfetamina|fentanilo|clonazepam|xanax|rivotril|benzodiazepina\w*|alcohol|cerveza|(tomar|tomo|bebio|botella de) vino|pisco|vodka|copete|chela|borrachera|vape\w*|tabaco|nicotina)\b""",
            ),
        ),
        Rule(
            "gambling",
            Regex(
                """\b(apuesta\w*|apostar|casino|ruleta|tragamonedas|loteria|bet|cuotas deportivas|parley|sportsbook|1xbet|bet365|dinero facil|pronosticos deportivos)\b""",
            ),
        ),
        Rule(
            "malestar",
            Regex(
                """\b(no aguanto (mas|nada)|me siento (solo|sola|mal|triste|vacio|vacia|ansios\w*|deprimid\w*)|estoy (cansad\w+|chata\w*) de todo|no quiero ir (al colegio|a clases)|me da miedo|nadie me (quiere|habla)|ataque de panico|ansiedad|depresion|no puedo dormir|lloro (todo el tiempo|sin parar)|me siento un fracaso|sin esperanza|no le veo sentido)\b""",
            ),
        ),
        Rule(
            "scam_points",
            Regex(
                """\b((canjea|canjear|canje|cambia) (tus|sus|los|las)? ?(puntos|recompensas|codigos?)|tus puntos (vencen|expiran|estan por vencer)|puntos (acumulados|disponibles|por vencer)|reclama (tus|el) (puntos|premio|recompensa)|codigo de canje|cupon(es)? (de descuento|exclusivo)|tarjeta de regalo|gift card|voucher|beneficio exclusivo|promocion exclusiva|ultimos dias para canjear|canjea antes)\b""",
            ),
        ),
        Rule(
            "scam_casino",
            Regex(
                """\b(casino (online|en linea|vip|premium)|bono (de bienvenida|sin deposito|de casino)|giros gratis|free spins|apuesta gratis|apuestas? (deportivas?|online)|codigo promocional (de casino|de apuestas)|jackpot|1xbet|bet365|betway|stake (casino|online)|sportsbook|tragamonedas|ruleta (online|virtual)|gana (dinero|premios) (facil|rapido)|multiplica tu dinero|bono de casino)\b""",
            ),
        ),
        Rule(
            "scam_financial",
            Regex(
                """\b(inversion (segura|garantizada|de alto rendimiento)|rendimiento (fijo|garantizado)|duplica (tu|el) dinero|ganancias? (diarias|garantizadas|faciles)|bitcoin|criptomoneda|cripto|usdt|binance|airtm|forex|trading (de|crypto)|mineria (de bitcoin|cripto)|invierte (desde|con)|capital (garantizado|inicial))\b""",
            ),
        ),
        Rule(
            "scam_account",
            Regex(
                """\b(tu cuenta (sera|ha sido|fue) (suspendida|bloqueada|cancelada|verificada)|verifica (tu|la) cuenta|confirma (tu|tus) (datos|identidad|informacion)|actualiza (tu|tus) (datos|informacion)|inicia sesion (aqui|en este enlace)|soporte tecnico (de|te)|codigo de verificacion|comparte (el|tu) codigo|otp|token de seguridad|banco (te|le) (informa|solicita)|haz clic (aqui|en el enlace)|enlace (seguro|oficial)|phishing|suplantacion|estafa)\b""",
            ),
        ),
        Rule(
            "scam_prize",
            Regex(
                """\b(ganaste (un|el) (premio|sorteo|iphone|playstation|dinero|viaje)|felicidades (has|te has) ganado|reclama (tu|el) premio|premio (gratis|gratuito|exclusivo)|sorteo (internacional|exclusivo)|loteria|rifa|iphone gratis|playstation gratis|ultimo aviso|beneficiario|premio (mayor|jackpot))\b""",
            ),
        ),
        Rule(
            "scam_delivery",
            Regex(
                """\b(paquete (retenido|en aduana|pendiente)|aduana (te|le) (solicita|requiere)|pago de (impuestos|tasa|arancel)|reprogramar (la )?entrega|tracking|numero de seguimiento|courier|correos de chile|tarifa de envio|envio (bloqueado|retenido))\b""",
            ),
        ),
        Rule(
            "scam_job",
            Regex(
                """\b(trabajo (desde casa|remoto|facil)|gana (dinero|hasta) (facil|desde casa|por dia)|ingresos? (extra|rapidos|diarios)|sin experiencia|pago diario|teletrabajo|entrevista (por|via) whatsapp|empresa internacional (te|le) (contrata|busca)|contratado (para|en) (trabajo|puesto)|solo (necesitas|requieres) (tu|un) (celular|telefono))\b""",
            ),
        ),
        Rule(
            "scam_romance",
            Regex(
                """\b(soldado (en|destinado) (el extranjero|afganistan|siria|irak)|medico (en|destinado) (el extranjero|mision)|necesito (dinero|ayuda) para (el pasaje|la visa|el vuelo)|mi amor (necesito|estoy en)|te amo (pero|y) necesito|conoci tus fotos|quiero conocerte (en persona|y casarme)|envia (dinero|regalo) para (la visa|el pasaje))\b""",
            ),
        ),
        Rule(
            "scam",
            Regex(
                """\b(ganaste (un|el) (premio|sorteo|iphone|dinero)|felicidades (has|te has) ganado|reclama (tu|el) premio|bitcoin|inversion (segura|garantizada)|duplica (tu|el) dinero|tu cuenta (sera|ha sido) (suspendida|bloqueada)|verifica (tu|la) cuenta|envia (el|tu) codigo de verificacion|comparte (el|tu) codigo|soporte tecnico (de|te)|phishing|estafa|premio (gratis|gratuito))\b""",
            ),
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
    private const val MAX_FLAGS = 14
}
