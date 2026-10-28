package cl.guardiania.agent.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun relativeTime(timestamp: Long?, now: Long = System.currentTimeMillis()): String {
    if (timestamp == null) return "Aún no"
    val diff = (now - timestamp).coerceAtLeast(0)
    return when {
        diff < 60_000L -> "hace un momento"
        diff < 3_600_000L -> "hace ${diff / 60_000L} min"
        diff < 86_400_000L -> "hace ${diff / 3_600_000L} h"
        else -> SimpleDateFormat("d MMM", Locale.forLanguageTag("es-CL")).format(Date(timestamp))
    }
}
