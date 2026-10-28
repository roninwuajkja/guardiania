package cl.guardiania.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.guardiania.agent.AgentUiState
import cl.guardiania.agent.BuildConfig

@Composable
fun StatusScreen(
    state: AgentUiState,
    onSync: () -> Unit,
    onTestSignal: () -> Unit,
    onUnpair: () -> Unit,
    onSwitchToTutor: (() -> Unit)? = null,
) {
    val pairing = state.pairing ?: return
    val permissions = rememberPermissionStatus()
    val context = LocalContext.current
    var confirmUnpair by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(Pine950),
        ) {
            PineBackdrop(Modifier.matchParentSize())
            Column(Modifier.padding(horizontal = 22.dp, vertical = 24.dp)) {
                BrandLockup(dark = true)
                Spacer(Modifier.height(30.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        text = "Vinculación activa",
                        background = Sage500.copy(alpha = 0.22f),
                        foreground = Sage300,
                        dotColor = Sage300,
                    )
                    if (state.pending > 0) {
                        Pill(
                            text = "${state.pending} por enviar",
                            background = Color.White.copy(alpha = 0.08f),
                            foreground = Color(0xFFEAF2ED),
                            dotColor = Color(0xFFD9A15B),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = pairing.deviceAlias,
                    color = Color(0xFFEAF2ED),
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tutor: ${pairing.tutorAlias}",
                    color = Sage300.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "VINCULADO ${relativeTime(pairing.linkedAt).uppercase()}",
                    color = Sage300.copy(alpha = 0.55f),
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.lastSyncAt
                        ?.let { "ÚLTIMA SEÑAL ${relativeTime(it).uppercase()}" }
                        ?: "AÚN SIN ENVÍOS AL PANEL",
                    color = Sage300.copy(alpha = 0.55f),
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                )
            }
        }

        Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
            PaperCard {
                Column(Modifier.padding(18.dp)) {
                    Eyebrow("Permisos del teléfono")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Tres accesos base para que la guardia funcione sin leer contenidos. La accesibilidad es opcional.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    Spacer(Modifier.height(16.dp))
                    PermissionRow(
                        icon = Icons.Outlined.BarChart,
                        title = "Acceso de uso",
                        description = "Minutos y horarios por aplicación.",
                        active = permissions.usageAccess,
                        onActivate = { openUsageAccess(context) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
                    PermissionRow(
                        icon = Icons.Outlined.Notifications,
                        title = "Acceso a notificaciones",
                        description = "Convierte avisos en conteos y señales. El texto se descarta en el teléfono.",
                        active = permissions.notificationAccess,
                        onActivate = { openNotificationAccess(context) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
                    PermissionRow(
                        icon = Icons.Outlined.BatterySaver,
                        title = "Batería sin restricciones",
                        description = "Para que el análisis siga en segundo plano.",
                        active = permissions.batteryUnrestricted,
                        onActivate = { requestBatteryExemption(context) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Line)
                    PermissionRow(
                        icon = Icons.Outlined.Accessibility,
                        title = "Contexto de pantalla · opcional",
                        description = "Solo registra qué app está en primer plano. No lee textos, contraseñas ni contenido de la pantalla.",
                        active = permissions.accessibility,
                        onActivate = { openAccessibilitySettings(context) },
                    )
                    if (!permissions.accessibility) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Opcional: algunos fabricantes bloquean las notificaciones en segundo plano. La guardia base funciona sin este acceso.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 11.5.sp,
                            color = Muted,
                        )
                    }

                    if (permissions.allReady) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            color = SageSoft,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = Pine700,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "Todo listo. La guardia corre en segundo plano.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Pine700,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            PaperCard {
                Column(Modifier.padding(18.dp)) {
                    Eyebrow("Envío")
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Stat(
                            label = "Pendientes",
                            value = state.pending.toString(),
                        )
                        Stat(
                            label = "Última sincronización",
                            value = relativeTime(state.lastSyncAt),
                            alignEnd = true,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onSync,
                        enabled = !state.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(17.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(Modifier.size(9.dp))
                            Text("Sincronizando…")
                        } else {
                            Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(9.dp))
                            Text("Sincronizar ahora")
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(
                        text = "En segundo plano cada 15 minutos, solo con red. Se envía un vector de señales.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 11.5.sp,
                        color = Muted,
                    )
                    if (state.error != null) {
                        Spacer(Modifier.height(12.dp))
                        InlineError(state.error)
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(14.dp))
                PaperCard {
                    Column(Modifier.padding(18.dp)) {
                        Eyebrow("Modo prueba · debug")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Probar el flujo completo",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Genera una señal local de apuestas y la sincroniza con el panel. No lee tus notificaciones.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onTestSignal,
                            enabled = !state.busy,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Outlined.Science, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Enviar señal de prueba")
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            PaperCard {
                Column(Modifier.padding(18.dp)) {
                    Eyebrow("Privacidad por diseño")
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Sale del teléfono",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink2,
                    )
                    Text(
                        text = "Categoría de la app, minutos de uso, conteos y marcas locales.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Se queda en el teléfono",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink2,
                    )
                    Text(
                        text = "Textos, fotos, audio, nombres de contacto y cualquier contenido de conversaciones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    PrivacyLine(
                        "Los datos locales se cifran con SQLCipher y se borran al desvincular.",
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            if (onSwitchToTutor != null) {
                TextButton(
                    onClick = onSwitchToTutor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cambiar a modo tutor/a")
                }
            }
            TextButton(
                onClick = { confirmUnpair = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = Oxblood),
            ) {
                Text("Desvincular este teléfono")
            }
            Text(
                text = "IA Guardian ${BuildConfig.VERSION_NAME} · Ley N.° 21.719",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Muted.copy(alpha = 0.8f),
                fontFamily = Mono,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.height(26.dp))
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("¿Desvincular este teléfono?") },
            text = {
                Text(
                    "Se borran los datos locales y se detiene el envío de señales. El tutor puede volver a vincularlo cuando quiera.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        onUnpair()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Oxblood),
                ) { Text("Desvincular") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    active: Boolean,
    onActivate: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(if (active) SageSoft else Card2, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) Pine700 else Muted,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 14.sp,
                )
                if (active) {
                    Pill(
                        text = "Activo",
                        background = SageSoft,
                        foreground = Pine700,
                        dotColor = Sage500,
                    )
                } else {
                    OutlinedButton(
                        onClick = onActivate,
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text("Activar", fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    alignEnd: Boolean = false,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            text = label.uppercase(),
            color = Muted,
            fontFamily = Mono,
            fontSize = 8.5.sp,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = Ink,
            fontFamily = if (value.all(Char::isDigit)) Mono else MaterialTheme.typography.titleMedium.fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (value.all(Char::isDigit)) 26.sp else 15.sp,
        )
    }
}
