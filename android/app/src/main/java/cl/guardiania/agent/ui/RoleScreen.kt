package cl.guardiania.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RoleChooserScreen(
    hasTutorSession: Boolean,
    hasPairing: Boolean,
    baseUrl: String,
    onTutor: () -> Unit,
    onChild: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val legalBase = baseUrl.trim().trimEnd('/')

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pine950)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .navigationBarsPadding(),
    ) {
        Spacer(Modifier.height(44.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GuardianMark(size = 38.dp, tint = Sage300, accent = Sage300)
            Spacer(Modifier.size(12.dp))
            Text(
                text = "IA GUARDIAN",
                color = Color(0xFFEAF2ED),
                fontFamily = Mono,
                fontSize = 11.sp,
                letterSpacing = 2.2.sp,
            )
        }

        Spacer(Modifier.height(34.dp))
        Text(
            text = "Un mismo teléfono,\ndos formas de cuidar.",
            color = Color(0xFFEAF2ED),
            style = MaterialTheme.typography.displaySmall,
            lineHeight = 42.sp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Elige cómo vas a usar esta instalación. Puedes cambiarlo después desde Ajustes.",
            color = Sage300.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodyLarge,
            lineHeight = 23.sp,
        )

        Spacer(Modifier.height(30.dp))
        RoleCard(
            icon = Icons.Outlined.AdminPanelSettings,
            title = if (hasTutorSession) "Continuar como tutor/a" else "Soy tutor/a",
            description = "Administra alertas, dispositivos y vinculaciones desde este teléfono.",
            onClick = onTutor,
            primary = true,
        )
        Spacer(Modifier.height(14.dp))
        RoleCard(
            icon = Icons.Outlined.PhoneAndroid,
            title = if (hasPairing) "Ver estado del menor" else "Este teléfono es del menor",
            description = "Activa la captura local de señales y vincula el equipo con un tutor.",
            onClick = onChild,
            primary = false,
        )

        Spacer(Modifier.height(28.dp))
        Text(
            text = "Diseñado para no leer conversaciones. La captura ocurre en el dispositivo y se envía solo un vector de señales.",
            color = Sage300.copy(alpha = 0.72f),
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                "privacidad" to "Privacidad",
                "terminos" to "Términos",
                "consentimiento" to "Consentimiento",
            ).forEach { (path, label) ->
                TextButton(
                    onClick = { runCatching { uriHandler.openUri("$legalBase/legal/$path") } },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                ) {
                    Text(label, color = Sage300, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(26.dp))
    }
}

@Composable
private fun RoleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (primary) Color(0xFFEAF2ED) else Color(0xFF123029),
        border = if (primary) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A5247)),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if (primary) Pine700.copy(alpha = 0.12f) else Color(0xFF1B4A3E),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (primary) Pine700 else Sage300,
                    modifier = Modifier.size(23.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (primary) Ink else Color(0xFFEAF2ED),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    color = if (primary) Ink2 else Sage300.copy(alpha = 0.82f),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
