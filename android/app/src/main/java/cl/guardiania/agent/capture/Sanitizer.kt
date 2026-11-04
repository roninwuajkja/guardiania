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
 * matches many patterns, the first flags kept for the vector are the ones that
 * matter most. Each regex works on normalized text (lowercase, no accents,
 * common leetspeak removed, repeated letters collapsed).
 */
object Sanitizer {

    data class Result(val category: String, val flags: List<String>)

    private data class Rule(val code: String, val pattern: Regex)

    private val shortenerHosts =
        "bit\\.ly|tinyurl\\.com|t\\.co|goo\\.gl|cutt\\.ly|rb\\.gy|is\\.gd|ow\\.ly|buff\\.ly|" +
            "rebrand\\.ly|shorturl\\.at|tiny\\.cc|bit\\.do|s\\.id|shorte\\.st|adf\\.ly|cutt\\.us|" +
            "v\\.gd|soo\\.gd|clck\\.ru|t\\.ly|short\\.gy|1link\\.st"

    private val scamLinkLures =
        "saldos?|giros?|bonos?|premios?|sorteos?|reclama|reclamos?|registro|registros?|registrate|" +
            "registrar|verifica|verificacion|cuentas?|suspendida|suspendido|bloqueada|bloqueado|" +
            "paquetes?|aduana|inversiones?|invierte|cripto|bitcoin|apuestas?|casino|tragamonedas|" +
            "jackpot|ganaste|has ganado|felicidades|beneficiario|tarjetas? de regalo|gift cards?|" +
            "cupones?|descuentos?|beneficios?|urgente|ultimas horas|confirma|contrasena|codigos?|" +
            "otp|soporte|banco|transferencias?|pagos?|deudas?|prestamos?|trabajos?|ingresos|" +
            "deposita|recarga|retira|ganancias|fortune horse|fortune tiger|fortune rabbit|" +
            "fortune mouse|blaze|betano|sportingbet|pixbet|superbet|novibet|parimatch|melbet|" +
            "mostbet|1win|pin-up|vavada|aviator|mines|tigrinho|jogo do bicho|betnacional|" +
            "estrela bet|galera bet|vaidebet|esportes da sorte|brasil bet"

    private val suspiciousLinkPattern =
        "(?:" +
            "(?:https?://|www\\.)(?:[a-z0-9-]+\\.)*$shortenerHosts(?:[/?#\\s.,;:!?)]|\\z)" +
            "|(?:https?://)(?:\\d{1,3}\\.){3}\\d{1,3}(?:[/:?#\\s.,;:!?)]|\\z)" +
            "|(?:https?://|www\\.)[^/\\s]*xn--[^/\\s]*" +
            "|(?:https?://)[^/?\\s]+@" +
            "|(?:https?://|www\\.)(?:[a-z0-9-]+\\.)+(?:top|xyz|click|link|live|buzz|icu|cam|rest|" +
            "monster|quest|cyou|sbs|cfd|work|support|security|accountant|zip|mov|vip|win|bid|loan|" +
            "download|racing|review|stream|gdn|men|date|party|science)(?:[/?#\\s.,;:!?)]|\\z)" +
            "|\\b(?:[a-z0-9-]+\\.)*$shortenerHosts/" +
            ")"

    private val scamLinkPattern = Regex(
        """(?=.*$suspiciousLinkPattern)(?=.*\b(?:$scamLinkLures)\b)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val rules = listOf(
        Rule(
            "self_harm",
            Regex(
                """\b(quiero (morir|desaparecer|no despertar)|me quiero (morir|matar|desaparecer|ir para siempre)|quitarme la vida|me quiero quitar la vida|autolesion\w*|me quiero cortar|quiero cortarme|cortarme (las|los) (brazos|piernas|venas|munecas)|suicid\w*|mejor (no estar|morir)|no quiero (vivir|seguir viviendo|estar aqui)|no quiero despertar (nunca|mas)|me quiero (ahorcar|colgar)|saltar (de|desde) (un|el) (edificio|puente)|carta de despedida|me despido (para siempre|de todos)|esta es mi ultima (carta|noche)|no le encuentro sentido a (la vida|vivir)|me quiero hacer dano|quiero descansar para siempre|mejor no haber nacido|ya no aguanto vivir|regalar mis cosas|testamento|sangre en (la banera|el piso)|pastillas para (morir|dormir para siempre))\b""",
            ),
        ),
        Rule(
            "grooming",
            Regex(
                """\b(manda(me)? (una )?(foto|video|pack)|mandame (fotos|videos|nudes)|no le (digas|cuentes) a (nadie|tus papas|tus padres|tu mama|tu papa)|nos vemos a solas|eres (muy )?madur\w+|guardemos (el|nuestro) secreto|secreto entre (nosotros|tu y yo)|borra (el chat|los mensajes|la conversacion)|no se lo cuentes a nadie|te doy (dinero|regalos) (si|a cambio)|(compra|pideme|enviame) (una )?tarjeta de regalo|te recojo|paso a buscarte|te llevo a (mi casa|un lugar)|donde vives|de que colegio|cuantos anos tienes|que edad tienes|te puedo (llamar|escribir) (a solas|por privado)|cambiate a (telegram|discord|snapchat)|borra (nuestras )?conversaciones|solo (nosotros|tu y yo)|eres mi (novia|novio) (secreta|virtual)|te quiero conocer (en persona|sola)|escapemos (juntos|juntas))\b""",
            ),
        ),
        Rule(
            "sextortion",
            Regex(
                """\b(tengo (tus|las) (fotos|imagenes|videos|capturas)|voy a (publicar|filtrar|exponer|enviar|compartir) (tus|las) (fotos|imagenes|videos|capturas)|si no (me )?(pagas|mandas (dinero|plata)|transfieres)|te voy a (funar|exponer|filtrar)|chantaje (sexual|con fotos)|sextorsion\w*|fotos (intimas|privadas) (a cambio|o publico)|paga o (publico|filtro)|todos van a ver (tus|las) (fotos|videos)|te voy a dejar (en ridiculo|como una puta)|tengo (tu|tus) (pack|nudes)|publico (tu|tus) (pack|nudes))\b""",
            ),
        ),
        Rule(
            "abuse",
            Regex(
                """\b(me (violo|violaron|manoseo|manosearon)|me (toco|tocaron) (las|los|mis) (partes|pecho|piernas|genitales|culo|tetas)|abuso (sexual|infantil)|violacion|tocamientos|(mi|el|la) (papa|padre|mama|madre|padrastro|madrastra|tio|tia|abuelo|abuela|hermano|hermana|primo|prima|vecino|profesor|entrenador|pololo|polola|pareja|novio|novia|ex) me (violo|manoseo|pega|golpea|maltrata|obligo a|toco (las|los|mis) (partes|pecho|piernas|genitales|culo|tetas))|me obliga a (desnudarme|tocarlo|tocarla|tener sexo)|me encierra (en|sin)|no me deja salir de (la casa|mi pieza)|me quema|me deja marcas|me golpea (mi|todos los dias)|me pega (mi|todos los dias)|maltrato (infantil|fisico|psicologico))\b""",
            ),
        ),
        Rule(
            "sexual_harassment",
            Regex(
                """\b(a ver (tu|ese|el) (culo|poto|trasero|tetas|pecho|cuerpo)|muestra(me)? (el|tu|ese) (culo|poto|trasero|tetas|pecho|cuerpo)|mandame (una )?(foto|video) (de tu cuerpo|sin ropa|desnud[ao])|quiero verte (desnud[ao]|sin ropa)|estas (rica|rico|buena|bueno)|que (rica|rico|buena|bueno) (estas|te ves)|te voy a (tocar|agarrar|manosear) (el|la|las|los|tu|tus) (culo|poto|teta|tetas|pecho|piernas|partes|genitales)|te voy a tocar (sin permiso|a la fuerza)|tocate (el|la) (culo|poto|teta|pecho)|quiero (tocar|chupar|morder) (tu|tus) (cuerpo|tetas|culo|poto|pecho)|me (exita|calienta) (tu|tus) (cuerpo|fotos|tetas|culo)|envia(me)? (una )?foto (hot|caliente|intima)|pack de (fotos|nudes)|nudes|fotos sin ropa|video hot|sexo oral|masturbate|masturbacion|correte|venirte)\b""",
            ),
        ),
        Rule(
            "trafficking",
            Regex(
                """\b(trata de (personas|blancas)|te van a (vender|llevar) (a otro pais|para trabajar)|prostitucion (infantil|juvenil)|servicios sexuales|cliente (mayor|adulto) (paga|quiere)|te obligan a (prostituirte|trabajar en la calle)|explotacion (sexual|laboral)|te compro|te vendo|te vamos a sacar del pais|pasaporte (falso|retenido)|deuda (con|por) (trafico|trata)|trabajar en la calle|masajes (con|sexuales)|te van a llevar (a un cliente|a otro pais))\b""",
            ),
        ),
        Rule(
            "radicalization",
            Regex(
                """\b(reclutar (para|a) (la yihad|el estado islamico|grupo armado)|unete (a la|al) (yihad|califato|estado islamico|al qaeda)|apoya (a|al) (estado islamico|al qaeda|yihad)|supremacia (blanca|aria)|raza superior|limpieza (etnica|social)|muerte a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas|mujeres)|guerra santa contra|reclutamiento (extremista|terrorista)|soy (nazi|fascista)|viva hitler|apoyo a hitler|el holocausto (fue bueno|no existio)|matar a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas)|odio a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas))\b""",
            ),
        ),
        Rule(
            "sexual_content",
            Regex(
                """\b(porno\w*|sexo|desnud\w*|fotos? (intimas?|hot|sin ropa)|pack de fotos|nudes?|onlyfans|xvideos|pornhub|video (sexual|intimo)|contenido (sexual|adulto)|menor (desnuda|desnudo)|tetas?|verga|pene|pija|vagina|concha|masturbar\w*|masturbacion|orgasmo|correrse|venirse|sexo oral|sexo anal|xxx)\b""",
            ),
        ),
        Rule(
            "violence",
            Regex(
                """\b(te voy a (pegar|matar|sacar la cresta|moler|patear|apunalar|disparar|quemar|ahorcar|violar|hacer dano)|voy a golpearte|golpiza|(me|te|nos) amenaza\w*|amenaza (de muerte|con (matar|pegar|arma|un arma))|machete|cuchillo|pelea|apunalar|punalar|tiroteo|bomba|atentado|quiero matar a|voy a matar a|me (pega|golpea) (en el colegio|todos los dias|sin razon)|sangre|herido|muerte)\b""",
            ),
        ),
        Rule(
            "threat",
            Regex(
                """\b(te voy a (tocar(?! (el timbre|la puerta|el hombro|la mano))|agarrar|encontrar|buscar|hacer algo|hacer dano|esperar)|vas a ver (lo que pasa|lo que te pasa)|te va a ir mal|te arrepentiras|no te voy a dejar en paz|te voy a funar|te voy a acusar|te voy a mandar a (matar|golpear)|te tengo (miedo|amenazado)|me vas a (pagar|devolver)|te voy a quitar|te voy a romper|te voy a destruir|te voy a arruinar|te voy a exponer|te voy a humillar|te voy a hacer la vida imposible|te voy a seguir|te estoy mirando|se donde (vives|estudias|vas al colegio))\b""",
            ),
        ),
        Rule(
            "weapons",
            Regex(
                """\b(pistola|revolver|escopeta|rifle|arma de fuego|tengo (un|una) arma|tengo (una|mi) pistola|balas|municiones|machete|navaja|punal|granada|explosivo|bomba casera|silenciador|fusil|taser|gas pimienta|manopla|arma blanca)\b""",
            ),
        ),
        Rule(
            "coercion",
            Regex(
                """\b(me obliga\w*|me amenaza\w*|si no (me )?(mandas|haces|respondes|pagas|vienes|obedeces)|te voy a funar|si le (cuentas|dices) (a alguien|a tus papas)|chantaje\w*|me esta obligando|me esta amenazando|si no haces lo que digo|vas a ver lo que pasa|te arrepentiras|no te voy a dejar en paz|me debes|paga o|si no pagas|si no respondes|te voy a hacer|te voy a quitar|te voy a acusar|te voy a exponer)\b""",
            ),
        ),
        Rule(
            "bullying",
            Regex(
                """\b(nadie te (quiere|habla)|todos te odian|no tienes amigos|no te juntes con|te van a (pegar|funar|sacar)|te vamos a (pegar|funar|sacar)|acos\w*|bullying|molest\w*|webe\w*|funar|eres un (perdedor|raro|fracasado|asco|basura)|no sirves para nada|me (pega|golpea) (en el colegio|todos los dias)|todos se rien de ti|te esconden|te quitan|te empujan|te encierran|te ignoran|ley del hielo|te hacen la vida imposible)\b""",
            ),
        ),
        Rule(
            "hate_speech",
            Regex(
                """\b(no te juntes con (negros|indios|venecos|sudacas|maricones|musulmanes|judios|trans|gays|lesbianas)|esos (negros|indios|venecos|sudacas|maricones|musulmanes|judios|trans|gays|lesbianas) (no deberian|tienen que)|muerte a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas|mujeres)|matar a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas)|limpieza (etnica|social)|supremacia (blanca|aria)|raza superior|odio a (los|las) (judios|musulmanes|inmigrantes|negros|gays|indigenas)|fuera (los|las) (inmigrantes|negros|indios|venecos|sudacas)|no deberian existir|maricon|puto|fleto|retrasado|mongolico|sudaca|veneco|indio de mierda|negro de mierda|judio de mierda|musulman de mierda)\b""",
            ),
        ),
        Rule(
            "insult_severe",
            Regex(
                """\b(hijo de puta|hija de puta|hijos de puta|conchetumadre|conchetumare|culiao|culiada|maricon|maricona|maricon de mierda|(eres|es|son|que) (un|una|unos|unas)? (puto|puta|perra|zorra|fleto|fleta|retrasado|retrasada)|retrasado mental|retrasada mental|perra de mierda|zorra de mierda|chupapico|chupapija|malparido|malparida|bastardo|bastarda|escoria|basura humana|mongolico|mongolica|sudaca|veneco|indio de mierda|negro de mierda|ojala te mueras|ojala te maten|muerete (de verdad|luego|ahora)|te voy a matar|te voy a violar|te voy a apunalar|te voy a disparar)\b""",
            ),
        ),
        Rule(
            "insult_moderate",
            Regex(
                """\b(idiota|imbecil|estupido|estupida|tarado|tarada|pelotudo|pelotuda|aweonao|aweonada|sacowea|saco de weas|huevon de mierda|weon de mierda|(eres|es|son|que) (un|una|unos|unas)? (huevon|huevona|weon|weona|gil|gila|cerdo|cerda|rata|asqueroso|asquerosa|patetico|patetica|arrastrado|arrastrada|chupamedias|perdedor|perdedora|fracasado|fracasada|raro|rara|basura|escoria|muerto de hambre|infeliz|miserable|lacra|parasito|parasita))\b""",
            ),
        ),
        Rule(
            "insult_mild",
            Regex(
                """\b(tonto|tonta|tontito|tontita|bobo|boba|ridiculo|ridicula|inutil|mediocre|lerdo|lerda|papanatas|zoquete|menso|mensa|blandengue|(eres|es|son|que) (un|una|unos|unas)? (payaso|payasa|pavo|pava|pesado|pesada|cargante|novato|novata|chistoso|chistosa|flojo|floja|lloron|llorona|mamon|mamona)|te odio|te odiamos|no te soporto|me caes mal|eres insoportable)\b""",
            ),
        ),
        Rule(
            "stalking",
            Regex(
                """\b(te estoy (siguiendo|mirando|observando|esperando)|se donde (vives|estudias|vas al colegio|trabajas)|te vi (en el colegio|en tu casa|en la calle)|no dejes de mirar atras|te voy a seguir|te estoy esperando (afuera|en la salida)|tengo tu ubicacion|comparte tu ubicacion|pasame tu ubicacion|te encontre|te acecho|acoso)\b""",
            ),
        ),
        Rule(
            "eating_disorder",
            Regex(
                """\b(no (he )?comido (en|hace|todo el dia)|deje de comer|vomito? (despues de comer|lo que como)|me provoco el vomito|purga(s)?|laxante\w*|ayuno (extremo|prolongado|de (varios|muchos) dias)|atracon\w*|trastorno (alimentario|de la conducta alimentaria)|anorexia|bulimia|ortorexia|calorias|conteo de calorias|me veo (gorda|gordo|flaca|flaco)|estoy (gorda|gordo|flaca|flaco)|quiero (bajar de peso|ser flaca|ser flaco)|dieta (extrema|peligrosa)|no merezco comer|comida es el enemigo)\b""",
            ),
        ),
        Rule(
            "doxxing",
            Regex(
                """\b(voy a (publicar|filtrar|exponer|doxxear) (tu|tus|su|sus) (direccion|numero|rut|colegio|fotos|datos|nombre|ubicacion)|tengo (tu|tus) (direccion|numero|rut|colegio|fotos|datos|ubicacion)|se donde (vives|estudias|vas al colegio|trabajas)|publicare (tu|tus) (direccion|numero|rut|fotos)|doxxear\w*|doxxing|exponer (tu|tus) (datos|fotos|direccion)|comparte (su|tu) (direccion|numero|rut|fotos)|pasame (su|tu) (direccion|numero|rut|fotos))\b""",
            ),
        ),
        Rule(
            "runaway",
            Regex(
                """\b(me voy a (arrancar|escapar|ir de casa|fugar)|quiero (arrancar|escapar|irme de casa)|no vuelvo (a casa|mas)|me voy de (la casa|mi casa)|huir de (casa|mi casa)|no quiero volver a casa|no me busquen|me voy a otro pais|tengo (el|un) pasaje|hice la mochila|me voy para siempre)\b""",
            ),
        ),
        Rule(
            "dangerous_challenge",
            Regex(
                """\b(ballena azul|reto (de la )?(ballena|asfixia|blackout|48 horas|desaparecer|muerte|sangre)|blackout challenge|reto viral (peligroso|mortal)|juego de la asfixia|48 horas desaparecido|desaparecer por 48|salto mortal|momo challenge|reto de momo|reto de saltar (de|desde)|reto de la oscuridad|reto de las cuchillas|reto de las pastillas|reto del veneno|reto de ahorcarse|reto de la silla|reto de la nube)\b""",
            ),
        ),
        Rule(
            "drugs",
            Regex(
                """\b(marihuana|(fumar|fumando|comprar|vender) pasto|droga\w*|cocaina|merca|falopa|pasta base|basuco|crack|heroina|opio|morfina|porro|(fumar|prender) pito|tussi|extasis|lsd|acido lisergico|hongos (alucinogenos|psilocibina)|ketamina|mdma|anfetamina|metanfetamina|fentanilo|clonazepam|xanax|rivotril|benzodiazepina\w*|tramadol|gabapentina|pregabalina|alcohol|cerveza|(tomar|tomo|bebio|botella de) vino|pisco|vodka|copete|chela|borrachera|(andar|estar) (curado|volado)|vape\w*|tabaco|nicotina|dealer|transa|vender (droga|pasto|merca|falopa)|comprar (droga|pasto|merca|falopa))\b""",
            ),
        ),
        Rule(
            "gambling",
            Regex(
                """\b(apuesta\w*|apostar|casino|ruleta|tragamonedas|loteria|rifa|bet|cuotas deportivas|parley|sportsbook|1xbet|bet365|betway|stake|jackpot|giros gratis|free spins|bono de casino|apuesta deportiva|pronosticos deportivos|dinero facil|multiplica tu dinero)\b""",
            ),
        ),
        Rule(
            "malestar",
            Regex(
                """\b(no aguanto (mas|nada)|me siento (solo|sola|mal|triste|vacio|vacia|ansios\w*|deprimid\w*|culpable|un fracaso)|estoy (cansad\w+|chata\w*|triste|solo|sola|ansios\w*|deprimid\w*) de todo|no quiero ir (al colegio|a clases)|me da miedo|nadie me (quiere|habla)|ataque de panico|ansiedad|depresion|no puedo dormir|lloro (todo el tiempo|sin parar)|me siento un fracaso|sin esperanza|no le veo sentido|no valgo nada|soy un estorbo|me quiero esconder|no quiero hablar con nadie|me siento mal|quiero llorar)\b""",
            ),
        ),
        Rule(
            "scam_points",
            Regex(
                """\b((canjea|canjear|canje|cambia) (tus|sus|los|las)? ?(puntos|recompensas|codigos?)|tus puntos (vencen|expiran|estan por vencer)|puntos (acumulados|disponibles|por vencer)|reclama (tus|el) (puntos|premio|recompensa)|codigo de canje|cupon(es)? (de descuento|exclusivo)|tarjeta de regalo|gift card|voucher|beneficio exclusivo|promocion exclusiva|ultimos dias para canjear|canjea antes|puntos (club|cencosud|falabella|ripley|latam|entel|movistar))\b""",
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
                """\b(inversion (segura|garantizada|de alto rendimiento)|rendimiento (fijo|garantizado)|duplica (tu|el) dinero|ganancias? (diarias|garantizadas|faciles)|bitcoin|criptomoneda|cripto|usdt|binance|airtm|forex|trading (de|crypto)|mineria (de bitcoin|cripto)|invierte (desde|con)|capital (garantizado|inicial)|opciones binarias|esquema piramidal|ponzi|multinivel|gana por invitar|referidos)\b""",
            ),
        ),
        Rule(
            "scam_account",
            Regex(
                """\b(tu cuenta (sera|ha sido|fue) (suspendida|bloqueada|cancelada|verificada)|verifica (tu|la) cuenta|confirma (tu|tus) (datos|identidad|informacion)|actualiza (tu|tus) (datos|informacion)|inicia sesion (aqui|en este enlace)|soporte tecnico (de|te)|codigo de verificacion|comparte (el|tu) codigo|otp|token de seguridad|banco (te|le) (informa|solicita)|haz clic (aqui|en el enlace)|enlace (seguro|oficial)|phishing|suplantacion|estafa|recupera tu cuenta|problema con tu pago|tarjeta bloqueada|cargo no reconocido)\b""",
            ),
        ),
        Rule("scam_link", scamLinkPattern),
        Rule(
            "scam_prize",
            Regex(
                """\b(ganaste (un|el) (premio|sorteo|iphone|playstation|dinero|viaje)|felicidades (has|te has) ganado|reclama (tu|el) premio|premio (gratis|gratuito|exclusivo)|sorteo (internacional|exclusivo)|loteria|rifa|iphone gratis|playstation gratis|ultimo aviso|beneficiario|premio (mayor|jackpot)|has sido seleccionado|ganador|reclama antes|cupon ganador)\b""",
            ),
        ),
        Rule(
            "scam_delivery",
            Regex(
                """\b(paquete (retenido|en aduana|pendiente)|aduana (te|le) (solicita|requiere)|pago de (impuestos|tasa|arancel)|reprogramar (la )?entrega|tracking|numero de seguimiento|courier|correos de chile|tarifa de envio|envio (bloqueado|retenido)|paquete pendiente|pago de liberacion|agencia aduanas)\b""",
            ),
        ),
        Rule(
            "scam_job",
            Regex(
                """\b(trabajo (desde casa|remoto|facil)|gana (dinero|hasta) (facil|desde casa|por dia)|ingresos? (extra|rapidos|diarios)|sin experiencia|pago diario|teletrabajo|entrevista (por|via) whatsapp|empresa internacional (te|le) (contrata|busca)|contratado (para|en) (trabajo|puesto)|solo (necesitas|requieres) (tu|un) (celular|telefono)|trabajo de medio tiempo|ganancias por tarea|pago por encuesta|asistente virtual|contratado inmediatamente|envia tu curriculum por whatsapp)\b""",
            ),
        ),
        Rule(
            "scam_romance",
            Regex(
                """\b(soldado (en|destinado) (el extranjero|afganistan|siria|irak)|medico (en|destinado) (el extranjero|mision)|necesito (dinero|ayuda) para (el pasaje|la visa|el vuelo)|mi amor (necesito|estoy en)|te amo (pero|y) necesito|conoci tus fotos|quiero conocerte (en persona|y casarme)|envia (dinero|regalo) para (la visa|el pasaje)|estoy en una mision|no puedo videollamada|mi cuenta esta bloqueada|ayudame con una transferencia)\b""",
            ),
        ),
        Rule(
            "scam",
            Regex(
                """\b(ganaste (un|el) (premio|sorteo|iphone|dinero)|felicidades (has|te has) ganado|reclama (tu|el) premio|bitcoin|inversion (segura|garantizada)|duplica (tu|el) dinero|tu cuenta (sera|ha sido) (suspendida|bloqueada)|verifica (tu|la) cuenta|envia (el|tu) codigo de verificacion|comparte (el|tu) codigo|soporte tecnico (de|te)|phishing|estafa|suplantacion|premio (gratis|gratuito)|canjea tus puntos|casino online|paquete retenido|trabajo desde casa|soldado en el extranjero)\b""",
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
        val raw = listOfNotNull(title, text).joinToString(" ").take(MAX_SCANNED_CHARS)
        val content = normalize(raw)
        val linkContent = normalizeForLinks(raw)
        val flags = rules
            .asSequence()
            .filter { rule ->
                rule.pattern.containsMatchIn(content) ||
                    (rule.code == "scam_link" && rule.pattern.containsMatchIn(linkContent))
            }
            .map { it.code }
            .distinct()
            .take(MAX_FLAGS)
            .toList()
        return Result(categoryFor(packageName), flags)
    }

    private fun normalizeForLinks(value: String): String {
        val cleaned = value.replace(zeroWidth, "")
        val decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
        return decomposed.replace(diacritics, "").lowercase(Locale.ROOT)
    }

    private fun normalize(value: String): String {
        val cleaned = value.replace(zeroWidth, "")
        val decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
        val lower = decomposed.replace(diacritics, "").lowercase(Locale.ROOT)
        val deobfuscated = lower
            .replace('0', 'o')
            .replace('1', 'i')
            .replace('3', 'e')
            .replace('4', 'a')
            .replace('5', 's')
            .replace('7', 't')
            .replace('@', 'a')
            .replace('$', 's')
        return repeatedLetters.replace(deobfuscated, "\$1")
    }

    private val zeroWidth = Regex("[\\u200B-\\u200D\\uFEFF\\u2060]")
    private val diacritics = Regex("\\p{Mn}+")
    private val repeatedLetters = Regex("([a-z])\\1{2,}")
    private const val MAX_SCANNED_CHARS = 4000
    private const val MAX_FLAGS = 16
}
