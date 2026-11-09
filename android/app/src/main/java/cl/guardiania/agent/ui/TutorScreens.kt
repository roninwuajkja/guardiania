package cl.guardiania.agent.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.guardiania.agent.TutorTab
import cl.guardiania.agent.TutorUiState
import cl.guardiania.agent.data.TutorAlertDto
import cl.guardiania.agent.data.TutorDeviceDto
import cl.guardiania.agent.data.TutorOverviewDto
import cl.guardiania.agent.data.TutorPairingDto
import cl.guardiania.agent.data.TutorPairingStatusDto
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TutorShell(
    state: TutorUiState,
    onRefresh: () -> Unit,
    onTab: (TutorTab) -> Unit,
    onAlertState: (String, String) -> Unit,
    onRevoke: (String) -> Unit,
    onSetUnlinkCode: (String, String) -> Unit,
    onClearUnlinkCode: (String) -> Unit,
    onGeneratePairing: (String, Boolean) -> Unit,
    onCancelPairing: () -> Unit,
    onClearPairing: () -> Unit,
    onLogout: () -> Unit,
    onLogoutAll: () -> Unit,
    onSwitchToChild: () -> Unit,
    onOpenLegal: (String) -> Unit,
    onResendVerification: () -> Unit,
    onChangePassword: (String, String) -> Unit,
    onExport: () -> Unit,
    onTestPush: () -> Unit,
) {
    Scaffold(
        containerColor = Paper,
        topBar = { TutorTopBar(state, onRefresh) },
        bottomBar = {
            TutorBottomBar(current = state.tab, onTab = onTab, openAlerts = state.overview?.openAlerts ?: 0)
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (state.tab) {
                TutorTab.INICIO -> TutorHome(
                    state = state,
                    onRefresh = onRefresh,
                    onResendVerification = onResendVerification,
                    onGoAlerts = { onTab(TutorTab.ALERTAS) },
                    onGoDevices = { onTab(TutorTab.EQUIPOS) },
                )
                TutorTab.ALERTAS -> TutorAlerts(state, onAlertState)
                TutorTab.EQUIPOS -> TutorDevices(
                    state = state,
                    onRevoke = onRevoke,
                    onSetUnlinkCode = onSetUnlinkCode,
                    onClearUnlinkCode = onClearUnlinkCode,
                    onGeneratePairing = onGeneratePairing,
                    onCancelPairing = onCancelPairing,
                    onClearPairing = onClearPairing,
                )
                TutorTab.AJUSTES -> TutorSettings(
                    state = state,
                    onRefresh = onRefresh,
                    onLogout = onLogout,
                    onLogoutAll = onLogoutAll,
                    onSwitchToChild = onSwitchToChild,
                    onOpenLegal = onOpenLegal,
                    onResendVerification = onResendVerification,
                    onChangePassword = onChangePassword,
                    onExport = onExport,
                    onTestPush = onTestPush,
                )
            }
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = Pine600,
                    trackColor = SageSoft,
                )
            }
        }
    }
}

@Composable
private fun TutorTopBar(state: TutorUiState, onRefresh: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Pine950)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GuardianMark(size = 28.dp, tint = Sage300, accent = Sage300)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "IA GUARDIAN · TUTOR",
                    color = Sage300,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    letterSpacing = 1.8.sp,
                )
                Text(
                    text = state.session?.alias ?: "Panel de cuidado",
                    color = Color(0xFFEAF2ED),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(
                Modifier
                    .size(34.dp)
                    .background(Color(0xFF1B4A3E), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (state.session?.alias ?: "T").trim().take(1).uppercase(),
                    color = Sage300,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onRefresh, enabled = !state.busy) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Actualizar",
                    tint = Sage300,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun TutorBottomBar(current: TutorTab, onTab: (TutorTab) -> Unit, openAlerts: Int) {
    NavigationBar(containerColor = Card, tonalElevation = 0.dp) {
        val items = listOf(
            Triple(TutorTab.INICIO, Icons.Outlined.Home, "Inicio"),
            Triple(TutorTab.ALERTAS, Icons.Outlined.Notifications, "Alertas"),
            Triple(TutorTab.EQUIPOS, Icons.Outlined.PhoneAndroid, "Equipos"),
            Triple(TutorTab.AJUSTES, Icons.Outlined.Settings, "Ajustes"),
        )
        items.forEach { (tab, icon, label) ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onTab(tab) },
                icon = {
                    Box {
                        Icon(icon, contentDescription = label, modifier = Modifier.size(21.dp))
                        if (tab == TutorTab.ALERTAS && openAlerts > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(7.dp)
                                    .background(Oxblood, CircleShape),
                            )
                        }
                    }
                },
                label = { Text(label, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Pine700,
                    selectedTextColor = Pine700,
                    indicatorColor = SageSoft,
                    unselectedIconColor = Muted,
                    unselectedTextColor = Muted,
                ),
            )
        }
    }
}

@Composable
private fun TutorHome(
    state: TutorUiState,
    onRefresh: () -> Unit,
    onResendVerification: () -> Unit,
    onGoAlerts: () -> Unit,
    onGoDevices: () -> Unit,
) {
    val overview = state.overview
    val liveDevices = overview?.devices?.count { it.deviceLive() } ?: 0
    val linkedLabel = if ((overview?.devices?.size ?: 0) == 1) "vinculado" else "vinculados"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = "Hola, ${state.session?.alias?.substringBefore(' ') ?: "tutor/a"}.",
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
            )
            Text(
                text = "Este es el pulso de cuidado de hoy. Las alertas son señales para conversar, no pruebas.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (state.session?.emailVerified == false) {
            item { VerifyEmailBanner(onResendVerification) }
        }

        if (overview == null) {
            item {
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Pine600)
                        Spacer(Modifier.width(12.dp))
                        Text("Cargando el panel…", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        } else {
            item { StatusHero(overview, liveDevices, linkedLabel, onGoAlerts) }
            item { MetricsRow(overview, liveDevices, linkedLabel) }
            item {
                SurfaceCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Últimos 7 días", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text("minutos de uso", color = Muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    WeekBars(overview)
                }
            }
            item {
                SectionHeader("Alertas recientes", if (overview.openAlerts > 0) "${overview.openAlerts} abiertas" else "Todo al día") {
                    TextButton(onClick = onGoAlerts) { Text("Ver todas") }
                }
            }
            if (state.alerts.isEmpty()) {
                item { EmptyNote("No hay alertas abiertas. Una semana tranquila también es información.") }
            } else {
                items(state.alerts.take(3), key = { it.id }) { alert ->
                    CompactAlert(alert)
                }
            }
            item {
                SectionHeader("Dispositivos", "${state.devices.count { it.deviceLive() }} con señal · ${state.devices.size} $linkedLabel") {
                    TextButton(onClick = onGoDevices) { Text("Gestionar") }
                }
            }
            if (state.devices.isEmpty()) {
                item { EmptyNote("Aún no hay dispositivos. Vincula el teléfono del menor desde Equipos.") }
            } else {
                items(state.devices, key = { it.id }) { device -> DeviceRow(device) }
            }
            item {
                TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Actualizar panel")
                }
            }
        }
    }
}

@Composable
private fun StatusHero(
    overview: TutorOverviewDto,
    liveDevices: Int,
    linkedLabel: String,
    onGoAlerts: () -> Unit,
) {
    val (background, accent) = when (overview.statusTone) {
        "critico" -> OxbloodSoft to OxbloodInk
        "atencion" -> AmberSoft to Amber
        else -> SageSoft to Pine700
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = background,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (overview.statusTone) {
                        "critico" -> "Requiere atención"
                        "atencion" -> "Para conversar"
                        else -> "En calma"
                    },
                    color = accent,
                    fontFamily = Mono,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.4.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = overview.statusLine,
                color = Ink,
                fontSize = 20.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "$liveDevices con señal · ${overview.devices.size} $linkedLabel · ${overview.minutesToday} min hoy",
                color = Ink2,
                fontSize = 12.sp,
            )
            if (overview.openAlerts > 0) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGoAlerts,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pine700),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(
                        "Revisar ${overview.openAlerts} ${if (overview.openAlerts == 1) "señal" else "señales"}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsRow(overview: TutorOverviewDto, liveDevices: Int, linkedLabel: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Metric("Con señal", liveDevices.toString(), "de ${overview.devices.size} $linkedLabel", Modifier.weight(1f))
        Metric("Alertas", overview.openAlerts.toString(), "abiertas", Modifier.weight(1f))
        Metric("Críticas", overview.criticalAlerts.toString(), "sin revisar", Modifier.weight(1f))
    }
}

@Composable
private fun Metric(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = Card, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(13.dp)) {
            Text(label.uppercase(), color = Muted, fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(caption, color = Muted, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun WeekBars(overview: TutorOverviewDto) {
    val max = (overview.week.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        overview.week.forEach { point ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier
                        .width(20.dp)
                        .height((12 + (point.minutes.toFloat() / max) * 58).dp)
                        .background(
                            if (point.alerts > 0) Amber.copy(alpha = 0.55f) else Sage500.copy(alpha = 0.45f),
                            RoundedCornerShape(6.dp),
                        ),
                )
                Spacer(Modifier.height(5.dp))
                Text(dayLabel(point.date), color = Muted, fontSize = 9.sp)
                Text("${point.minutes}", color = Muted, fontSize = 8.5.sp)
            }
        }
    }
}

@Composable
private fun CompactAlert(alert: TutorAlertDto) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Card,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (alert.isCritical()) Oxblood.copy(alpha = 0.28f) else Line,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(
                            severityColor(alert.severidad).copy(alpha = 0.12f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        alert.severidad,
                        color = severityColor(alert.severidad),
                        fontFamily = Mono,
                        fontSize = 8.sp,
                        letterSpacing = 0.7.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(categoryLabel(alert.categoria), color = Muted, fontSize = 10.5.sp)
                Spacer(Modifier.weight(1f))
                Text(prettyTime(alert.createdAt), color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                alert.resumen,
                color = Ink,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(alert.ownerLine(), color = Muted, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun TutorAlerts(state: TutorUiState, onAlertState: (String, String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("ABIERTAS") }
    val filtered = remember(state.alerts, filter) {
        when (filter) {
            "CRITICAS" -> state.alerts.filter { it.isCritical() }
            "ABIERTAS" -> state.alerts.filter { it.estado == "NUEVA" || it.estado == "VISTA" }
            else -> state.alerts
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Alertas", style = MaterialTheme.typography.headlineMedium, color = Ink)
            Text(
                "Léelas como una invitación a conversar. No son evidencia ni diagnóstico.",
                color = Muted,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ABIERTAS" to "Abiertas", "CRITICAS" to "Críticas", "TODAS" to "Todas").forEach { (key, label) ->
                    FilterChip(
                        selected = filter == key,
                        onClick = { filter = key },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SageSoft,
                            selectedLabelColor = Pine700,
                        ),
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item { EmptyNote("No hay alertas en este filtro.") }
        } else {
            items(filtered, key = { it.id }) { alert ->
                FullAlert(alert, onAlertState)
            }
        }
    }
}

@Composable
private fun FullAlert(alert: TutorAlertDto, onAlertState: (String, String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Card,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (alert.isCritical()) Oxblood.copy(alpha = 0.3f) else Line,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(severityColor(alert.severidad).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        alert.severidad,
                        color = severityColor(alert.severidad),
                        fontFamily = Mono,
                        fontSize = 8.5.sp,
                        letterSpacing = 0.7.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(categoryLabel(alert.categoria), color = Muted, fontSize = 10.5.sp)
                Spacer(Modifier.weight(1f))
                Text(alert.estado, color = Muted, fontFamily = Mono, fontSize = 8.5.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(alert.resumen, color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "${alert.ownerLine()} · ${prettyTime(alert.createdAt)}",
                color = Muted,
                fontSize = 10.5.sp,
            )
            if (alert.reglas.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    alert.reglas.mapNotNull { it.label ?: it.code }.take(4).joinToString(" · "),
                    color = Muted,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (alert.estado == "NUEVA") {
                    TextButton(onClick = { onAlertState(alert.id, "VISTA") }) { Text("Marcar vista") }
                }
                if (alert.estado != "RECONOCIDA") {
                    TextButton(onClick = { onAlertState(alert.id, "RECONOCIDA") }) { Text("Reconocer") }
                }
                if (alert.estado != "DESCARTADA") {
                    TextButton(
                        onClick = { onAlertState(alert.id, "DESCARTADA") },
                        colors = ButtonDefaults.textButtonColors(contentColor = Muted),
                    ) { Text("Descartar") }
                }
            }
        }
    }
}

private enum class DeviceFilter { ACTIVOS, TODOS }

@Composable
private fun TutorDevices(
    state: TutorUiState,
    onRevoke: (String) -> Unit,
    onSetUnlinkCode: (String, String) -> Unit,
    onClearUnlinkCode: (String) -> Unit,
    onGeneratePairing: (String, Boolean) -> Unit,
    onCancelPairing: () -> Unit,
    onClearPairing: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(DeviceFilter.ACTIVOS) }
    var revokeTarget by remember { mutableStateOf<TutorDeviceDto?>(null) }
    var unlinkCodeTarget by remember { mutableStateOf<TutorDeviceDto?>(null) }
    val pairing = state.pairing
    val filtered = state.devices.filter { device ->
        filter == DeviceFilter.TODOS || device.vinculacionEstadoActivo()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Equipos", style = MaterialTheme.typography.headlineMedium, color = Ink)
            Text(
                "Vincula el teléfono del menor con su consentimiento. La captura se queda en el dispositivo.",
                color = Muted,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )
        }
        if (pairing == null) {
            item {
                PairingGenerator(
                    busy = state.busy,
                    onGenerate = onGeneratePairing,
                )
            }
        } else {
            item {
                PairingLive(
                    pairing = pairing,
                    status = state.pairingStatus,
                    onCancel = onCancelPairing,
                    onClear = onClearPairing,
                )
            }
        }
        item {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter == DeviceFilter.ACTIVOS,
                    onClick = { filter = DeviceFilter.ACTIVOS },
                    label = { Text("Activos", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SageSoft),
                )
                FilterChip(
                    selected = filter == DeviceFilter.TODOS,
                    onClick = { filter = DeviceFilter.TODOS },
                    label = { Text("Todos", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SageSoft),
                )
            }
        }
        if (filtered.isEmpty()) {
            item { EmptyNote("No hay dispositivos en esta vista.") }
        } else {
            items(filtered, key = { it.id }) { device ->
                DeviceCard(
                    device = device,
                    onRevoke = { revokeTarget = device },
                    onUnlinkCode = { unlinkCodeTarget = device },
                )
            }
        }
    }

    if (revokeTarget != null) {
        GuardianDialog(
            icon = Icons.Outlined.Delete,
            title = "¿Revocar este dispositivo?",
            onDismiss = { revokeTarget = null },
            iconTint = Oxblood,
            iconBackground = OxbloodSoft,
            content = {
                DialogBodyText(
                    "El teléfono dejará de sincronizar de inmediato. Sus datos locales se borrarán en la próxima apertura de la app.",
                )
                Spacer(Modifier.height(10.dp))
                DialogNote("Las alertas y el historial se conservan.")
            },
            actions = {
                DialogPrimaryButton(
                    text = "Revocar",
                    onClick = {
                        revokeTarget?.bindingId?.let(onRevoke)
                        revokeTarget = null
                    },
                    destructive = true,
                )
                DialogSecondaryButton("Cancelar") { revokeTarget = null }
            },
        )
    }
    if (unlinkCodeTarget != null) {
        var code by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var dialogError by remember { mutableStateOf<String?>(null) }
        val target = unlinkCodeTarget
        GuardianDialog(
            icon = Icons.Outlined.Lock,
            title = if (target?.unlinkCodeSet == true) {
                "Cambiar código de desvinculación"
            } else {
                "Definir código de desvinculación"
            },
            onDismiss = { unlinkCodeTarget = null },
            iconTint = Pine700,
            iconBackground = SageSoft,
            content = {
                DialogBodyText(
                    "El teléfono pedirá este PIN de 6 dígitos para desvincularse. Tú siempre puedes revocar el acceso sin él.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter(Char::isDigit).take(6); dialogError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Código de 6 dígitos") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                    colors = dialogFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { value -> confirm = value.filter(Char::isDigit).take(6); dialogError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Repetir código") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                    colors = dialogFieldColors(),
                )
                if (dialogError != null) {
                    Spacer(Modifier.height(8.dp))
                    DialogErrorText(dialogError.orEmpty())
                }
                if (target?.unlinkCodeSet == true) {
                    Spacer(Modifier.height(10.dp))
                    DialogNote("Al guardar, el código anterior dejará de funcionar.")
                }
            },
            actions = {
                DialogPrimaryButton(
                    text = "Guardar código",
                    enabled = code.length == 6 && confirm.length == 6,
                    onClick = {
                        when {
                            code.length != 6 -> dialogError = "El código debe tener 6 dígitos."
                            confirm != code -> dialogError = "Los códigos no coinciden."
                            else -> {
                                target?.bindingId?.let { onSetUnlinkCode(it, code) }
                                unlinkCodeTarget = null
                            }
                        }
                    },
                )
                if (target?.unlinkCodeSet == true) {
                    TextButton(
                        onClick = {
                            target.bindingId?.let(onClearUnlinkCode)
                            unlinkCodeTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = Oxblood),
                    ) {
                        Text("Quitar código actual", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
                DialogSecondaryButton("Cancelar") { unlinkCodeTarget = null }
            },
        )
    }
}

@Composable
private fun PairingGenerator(busy: Boolean, onGenerate: (String, Boolean) -> Unit) {
    var childLabel by rememberSaveable { mutableStateOf("") }
    var declared by rememberSaveable { mutableStateOf(false) }
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(SageSoft, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Link, contentDescription = null, tint = Pine700, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("Vincular un teléfono", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 15.sp)
                Text("Genera un QR o un código de 6 dígitos.", color = Muted, fontSize = 11.5.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = childLabel,
            onValueChange = { childLabel = it.take(40) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Alias del menor (opcional)") },
            placeholder = { Text("Ej. Sofía") },
            singleLine = true,
            shape = RoundedCornerShape(11.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pine700, focusedLabelColor = Pine700),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = declared,
                onCheckedChange = { declared = it },
                colors = CheckboxDefaults.colors(checkedColor = Pine700),
            )
            Text(
                "Declaro que ejerzo la patria potestad, tutela o cuidado personal del menor y que le expliqué el funcionamiento.",
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 10.dp),
                color = Ink2,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onGenerate(childLabel, declared) },
            enabled = !busy && declared,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(11.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Generar vinculación")
        }
    }
}

@Composable
private fun PairingLive(
    pairing: TutorPairingDto,
    status: TutorPairingStatusDto?,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val bitmap = remember(pairing.qrPayload) { qrBitmap(pairing.qrPayload) }
    val state = status?.estado ?: "PENDIENTE"
    val complete = state == "VERIFICADA" || status?.bindingState == "ACTIVO"
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.Link,
                contentDescription = null,
                tint = if (complete) Pine700 else Amber,
                modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.width(9.dp))
            Column {
                Text(
                    when (state) {
                        "VERIFICADA" -> "Dispositivo vinculado"
                        "EXPIRADA" -> "Código expirado"
                        "CANCELADA" -> "Vinculación cancelada"
                        else -> "Esperando al dispositivo"
                    },
                    color = Ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Text(
                    status?.deviceAlias?.let { "Equipo: $it" } ?: "Pídele al menor escanear el QR o escribir el código.",
                    color = Muted,
                    fontSize = 11.5.sp,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (complete) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(SageSoft, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("La vinculación está activa. Ya puedes ver sus señales en el panel.", color = Pine700, fontSize = 13.sp)
            }
        } else {
            if (bitmap != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Código QR de vinculación",
                        modifier = Modifier
                            .size(224.dp)
                            .background(Color.White, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Código manual",
                    color = Muted,
                    fontFamily = Mono,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                )
                SelectionContainer {
                    Text(
                        pairing.manualCode,
                        color = Ink,
                        fontFamily = Mono,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 6.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(pairing.manualCode.replace(" ", ""))) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copiar código", fontSize = 12.sp)
                }
                Text(
                    "El código expira en 10 minutos. Si no se usa, genera uno nuevo.",
                    color = Muted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!complete && state == "PENDIENTE") {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar") }
            }
            Button(
                onClick = onClear,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (complete) Pine700 else Card2,
                    contentColor = if (complete) Color.White else Ink,
                ),
            ) { Text(if (complete) "Listo" else "Cerrar") }
        }
    }
}

@Composable
private fun DeviceRow(device: TutorDeviceDto) {
    val active = device.vinculacionEstadoActivo()
    val live = device.deviceLive()
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (live) Pine600 else if (active) Amber else Muted.copy(alpha = 0.4f),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(device.displayTitle(), color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(
                    "${device.displaySubtitle()} · Última conexión ${prettyTime(device.lastSeen)}",
                    color = Muted,
                    fontSize = 10.5.sp,
                )
            }
            Text(
                when {
                    live -> "Con señal"
                    active -> "Sin señal"
                    else -> "Inactivo"
                },
                color = when {
                    live -> Pine700
                    active -> Amber
                    else -> Muted
                },
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun DeviceCard(device: TutorDeviceDto, onRevoke: () -> Unit, onUnlinkCode: () -> Unit) {
    val active = device.vinculacionEstadoActivo()
    val stale = isOlderThan(device.lastSeen, 120)
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(
                            if (active && !stale) SageSoft else if (active) AmberSoft else Card2,
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.PhoneAndroid,
                        contentDescription = null,
                        tint = if (active && !stale) Pine700 else if (active) Amber else Muted,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.displayTitle(), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        device.displaySubtitle(),
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                Box(
                    Modifier
                        .background(
                            when {
                                !active -> OxbloodSoft
                                stale -> AmberSoft
                                else -> SageSoft
                            },
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        when {
                            !active -> "REVOCADO"
                            stale -> "SIN SEÑAL"
                            else -> "ACTIVO"
                        },
                        color = when {
                            !active -> OxbloodInk
                            stale -> Amber
                            else -> Pine700
                        },
                        fontFamily = Mono,
                        fontSize = 8.sp,
                        letterSpacing = 0.7.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Última conexión: ${prettyTime(device.lastSeen)}",
                color = Muted,
                fontSize = 10.5.sp,
            )
            val statusBits = buildList {
                if (device.notificationsActive == false) add("Notificaciones desactivadas")
                else if (device.notificationsActive == true) add("Notificaciones activas")
                if (device.dnd == true) add("No molestar")
                if (device.unlinkCodeSet) add("Código de desvinculación activo")
                device.lastNotificationAt?.let { add("última notificación ${prettyTime(it)}") }
            }
            when {
                stale -> Text(
                    "Sin señal desde ${prettyTime(device.lastSeen)}",
                    color = Amber,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                device.notificationsActive == false -> Text(
                    "Acceso a notificaciones desactivado",
                    color = Oxblood,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                statusBits.isNotEmpty() -> Text(
                    statusBits.joinToString(" · "),
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
            }
            if (active && device.bindingId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onUnlinkCode,
                        colors = ButtonDefaults.textButtonColors(contentColor = Pine700),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (device.unlinkCodeSet) "Cambiar código" else "Definir código",
                            fontSize = 11.5.sp,
                        )
                    }
                    TextButton(
                        onClick = onRevoke,
                        colors = ButtonDefaults.textButtonColors(contentColor = Oxblood),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Revocar acceso", fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorSettings(
    state: TutorUiState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onLogoutAll: () -> Unit,
    onSwitchToChild: () -> Unit,
    onOpenLegal: (String) -> Unit,
    onResendVerification: () -> Unit,
    onChangePassword: (String, String) -> Unit,
    onExport: () -> Unit,
    onTestPush: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmChild by remember { mutableStateOf(false) }
    var changePasswordOpen by remember { mutableStateOf(false) }
    var confirmLogoutAll by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = Ink)
        SurfaceCard {
            Text("Cuenta", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            SettingRow("Alias", state.session?.alias ?: "—")
            SettingRow("Correo", state.session?.email ?: "—")
            SettingRow("Correo verificado", if (state.session?.emailVerified == true) "Sí" else "Pendiente")
            SettingRow("Versión legal", "2026-10-22")
            Spacer(Modifier.height(8.dp))
            if (state.session?.emailVerified == false) {
                TextButton(onClick = onResendVerification, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) {
                    Text("Reenviar correo de verificación")
                }
            }
            TextButton(onClick = onRefresh, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Actualizar datos de la cuenta")
            }
        }

        SurfaceCard {
            Text("Notificaciones", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                "Envía una push de prueba con 10 segundos de retraso. Toca, cierra la app y espera.",
                color = Muted,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(9.dp))
            OutlinedButton(
                onClick = onTestPush,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Probar notificación push")
            }
            state.message?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(message, color = Pine700, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
        }

        SurfaceCard {
            Text("Seguridad", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { changePasswordOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Cambiar contraseña")
            }
            TextButton(
                onClick = { confirmLogoutAll = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 5.dp),
            ) {
                Text("Cerrar sesión en todos los dispositivos", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start, fontSize = 13.sp)
            }
        }

        SurfaceCard {
            Text("Mis datos", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                "Descarga un JSON con lo que IA Guardian conoce: tu cuenta, dispositivos, consentimientos, señales y alertas.",
                color = Muted,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(9.dp))
            Button(
                onClick = onExport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(11.dp),
            ) { Text("Exportar mis datos") }
            Spacer(Modifier.height(6.dp))
            Text(
                "La eliminación definitiva de la cuenta se hace desde la versión web, pidiendo tu contraseña y la frase ELIMINAR MI CUENTA.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        SurfaceCard {
            Text("Documentos y derechos", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            listOf(
                "privacidad" to "Política de privacidad",
                "terminos" to "Términos de uso",
                "consentimiento" to "Consentimiento informado",
                "seguridad" to "Seguridad y reporte",
                "retencion" to "Retención y eliminación",
            ).forEach { (path, label) ->
                TextButton(
                    onClick = { onOpenLegal(path) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 5.dp),
                ) {
                    Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start, fontSize = 13.sp)
                }
            }
            Text(
                "Puedes exportar tus datos aquí al lado. La eliminación definitiva se hace desde la versión web del panel.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        SurfaceCard {
            Text("Historial de consentimientos", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                "La prueba de qué aceptaste y cuándo: tutoría, términos, privacidad y asentimientos del menor.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(9.dp))
            if (state.consents.isEmpty()) {
                Text("Sin registros visibles todavía.", color = Muted, fontSize = 11.5.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.consents.take(6).forEach { consent ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(consentKindLabel(consent.tipo), color = Ink, fontSize = 12.sp)
                                Text(
                                    "Versión ${consent.version} · ${prettyTime(consent.creadoEn)}",
                                    color = Muted,
                                    fontSize = 10.5.sp,
                                )
                            }
                            Text(
                                if (consent.aceptado) "ACEPTADO" else "REVOCADO",
                                color = if (consent.aceptado) Pine700 else OxbloodInk,
                                fontFamily = Mono,
                                fontSize = 8.sp,
                                letterSpacing = 0.7.sp,
                            )
                        }
                    }
                    if (state.consents.size > 6) {
                        Text(
                            "+${state.consents.size - 6} registros anteriores.",
                            color = Muted,
                            fontSize = 10.5.sp,
                        )
                    }
                }
            }
        }

        SurfaceCard {
            Text("Modo de este teléfono", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                "Si este equipo es el del menor, activa la captura local y vincúlalo con tu cuenta.",
                color = Muted,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmChild = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Usar como dispositivo del menor")
            }
        }

        SurfaceCard {
            Text("Límites y transparencia", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "IA Guardian no lee el texto de tus conversaciones. La app del menor deriva señales localmente y el servidor rechaza contenido crudo. Las alertas pueden tener falsos positivos.",
                color = Muted,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Esta versión está endurecida para producción, pero requiere revisión legal chilena y auditoría de seguridad independiente antes de operar con datos reales de menores.",
                color = Amber,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        TextButton(
            onClick = { confirmLogout = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = Oxblood),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cerrar sesión")
        }
        Text(
            "IA Guardian · Borrador de producción · 2026",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = Muted,
            fontFamily = Mono,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(16.dp))
    }

    if (confirmLogout) {
        GuardianDialog(
            icon = Icons.AutoMirrored.Outlined.Logout,
            title = "¿Cerrar sesión?",
            onDismiss = { confirmLogout = false },
            iconTint = Oxblood,
            iconBackground = OxbloodSoft,
            content = {
                DialogBodyText(
                    "Se borrarán las credenciales del tutor de este teléfono. La sesión del menor, si existe, se mantiene.",
                )
            },
            actions = {
                DialogPrimaryButton(
                    text = "Cerrar sesión",
                    onClick = { confirmLogout = false; onLogout() },
                    destructive = true,
                )
                DialogSecondaryButton("Cancelar") { confirmLogout = false }
            },
        )
    }
    if (confirmLogoutAll) {
        GuardianDialog(
            icon = Icons.AutoMirrored.Outlined.Logout,
            title = "¿Cerrar todas las sesiones?",
            onDismiss = { confirmLogoutAll = false },
            iconTint = Oxblood,
            iconBackground = OxbloodSoft,
            content = {
                DialogBodyText(
                    "Revocará esta sesión y cualquier otra del tutor en la web o en otro teléfono. Tendrás que volver a entrar.",
                )
            },
            actions = {
                DialogPrimaryButton(
                    text = "Cerrar todas",
                    onClick = { confirmLogoutAll = false; onLogoutAll() },
                    destructive = true,
                )
                DialogSecondaryButton("Cancelar") { confirmLogoutAll = false }
            },
        )
    }
    if (changePasswordOpen) {
        var current by remember { mutableStateOf("") }
        var next by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var dialogError by remember { mutableStateOf<String?>(null) }
        GuardianDialog(
            icon = Icons.Outlined.Lock,
            title = "Cambiar contraseña",
            onDismiss = { changePasswordOpen = false },
            iconTint = Pine700,
            iconBackground = SageSoft,
            content = {
                DialogBodyText(
                    "Al cambiarla se cierran todas las sesiones del tutor, incluida esta. Vuelve a entrar con la nueva.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it; dialogError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Contraseña actual") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                    colors = dialogFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = next,
                    onValueChange = { next = it; dialogError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Contraseña nueva (mínimo 12)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                    colors = dialogFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; dialogError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Repetir contraseña nueva") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(11.dp),
                    colors = dialogFieldColors(),
                )
                if (dialogError != null) {
                    Spacer(Modifier.height(8.dp))
                    DialogErrorText(dialogError.orEmpty())
                }
            },
            actions = {
                DialogPrimaryButton(
                    text = "Cambiar contraseña",
                    onClick = {
                        when {
                            current.isBlank() -> dialogError = "Escribe tu contraseña actual."
                            next.length < 12 -> dialogError = "La nueva debe tener 12 caracteres o más."
                            next != confirm -> dialogError = "La confirmación no coincide."
                            current == next -> dialogError = "La nueva debe ser distinta de la actual."
                            else -> {
                                changePasswordOpen = false
                                onChangePassword(current, next)
                            }
                        }
                    },
                )
                DialogSecondaryButton("Cancelar") { changePasswordOpen = false }
            },
        )
    }
    if (confirmChild) {
        GuardianDialog(
            icon = Icons.Outlined.PhoneAndroid,
            title = "¿Usar este teléfono como dispositivo del menor?",
            onDismiss = { confirmChild = false },
            iconTint = Pine700,
            iconBackground = SageSoft,
            content = {
                DialogBodyText(
                    "Se abrirá la vinculación. El tutor debe estar presente y el menor debe dar su asentimiento en este equipo.",
                )
            },
            actions = {
                DialogPrimaryButton(
                    text = "Continuar",
                    onClick = { confirmChild = false; onSwitchToChild() },
                )
                DialogSecondaryButton("Cancelar") { confirmChild = false }
            },
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ink, fontSize = 12.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun VerifyEmailBanner(onResend: () -> Unit) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(AmberSoft, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Warning, contentDescription = null, tint = Amber, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Revisa tu correo", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    "Toca el enlace que enviamos. Sin verificar, la recuperación no estará activa en este panel.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            TextButton(onClick = onResend) { Text("Reenviar", fontSize = 11.5.sp) }
        }
    }
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Pine700,
    focusedLabelColor = Pine700,
)

private fun consentKindLabel(tipo: String): String = when (tipo.trim().lowercase()) {
    "terminos", "términos" -> "Términos de uso"
    "privacidad" -> "Política de privacidad"
    "tutoria", "tutoría" -> "Declaración de tutoría"
    "asentimiento_menor" -> "Asentimiento del menor"
    else -> tipo.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun SectionHeader(title: String, caption: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Text(caption, color = Muted, fontSize = 10.5.sp)
        }
        action()
    }
}

@Composable
private fun SurfaceCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Card2, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

private fun TutorAlertDto.ownerLine(): String {
    val child = childLabel?.trim()?.takeIf { it.isNotBlank() }
    val device = deviceAlias.trim()
    val shortDevice = if (child != null && device.startsWith(child)) {
        device.removePrefix(child).trim(' ', '·', '-')
    } else {
        device
    }
    return listOfNotNull(child, shortDevice.takeIf { it.isNotBlank() && it != child })
        .distinct()
        .joinToString(" · ")
        .ifBlank { "Dispositivo" }
}

private fun TutorDeviceDto.displayTitle(): String =
    childLabel?.trim()?.takeIf { it.isNotBlank() } ?: alias

private fun TutorDeviceDto.displaySubtitle(): String {
    val title = displayTitle()
    val model = modelFamily?.trim()?.takeIf { it.isNotBlank() }
    return when {
        model != null && !title.contains(model, ignoreCase = true) -> model
        alias.trim() != title && !title.contains(alias.trim(), ignoreCase = true) -> alias.trim()
        else -> "Dispositivo Android"
    }
}

private fun categoryLabel(raw: String): String = when (raw.trim().lowercase()) {
    "gambling", "apuestas", "betting" -> "Apuestas"
    "bullying", "acoso", "acoso_escolar" -> "Acoso escolar"
    "grooming" -> "Contacto riesgoso"
    "self_harm", "autolesion", "autolesión" -> "Autolesión"
    "sextortion" -> "Sextorsión"
    "abuse" -> "Abuso o maltrato"
    "trafficking" -> "Trata o explotación"
    "radicalization" -> "Radicalización"
    "sexual_content" -> "Contenido sexual"
    "sexual_harassment" -> "Acoso sexual"
    "violence", "violencia" -> "Violencia"
    "threat" -> "Amenaza"
    "weapons" -> "Armas"
    "coercion" -> "Presión o chantaje"
    "hate_speech" -> "Discurso de odio"
    "insult_severe" -> "Insulto grave"
    "insult_moderate" -> "Insulto moderado"
    "insult_mild" -> "Insulto leve"
    "eating_disorder" -> "Trastorno alimentario"
    "doxxing" -> "Datos expuestos"
    "stalking" -> "Acecho"
    "runaway" -> "Riesgo de fuga"
    "dangerous_challenge" -> "Reto peligroso"
    "drugs", "substance", "drogas", "consumo" -> "Drogas o alcohol"
    "scam_points" -> "Canje de puntos"
    "scam_casino" -> "Invitación a casino"
    "scam_financial" -> "Inversión sospechosa"
    "scam_account" -> "Phishing o suplantación"
    "scam_link" -> "Enlace sospechoso"
    "scam_prize" -> "Premio sospechoso"
    "scam_delivery" -> "Envío sospechoso"
    "scam_job" -> "Trabajo sospechoso"
    "scam_romance" -> "Estafa romántica"
    "scam" -> "Posible estafa"
    "unknown_contact" -> "Contacto desconocido"
    "ai_ciberbullying" -> "Ciberbullying"
    "ai_conducta_riesgo" -> "Conducta de riesgo"
    "ai_contenido_inapropiado" -> "Contenido inapropiado"
    "ai_sin_riesgo" -> "Sin riesgo"
    "malestar" -> "Malestar emocional"
    "sin_senal" -> "Teléfono sin señal"
    "night_use", "horario_nocturno" -> "Uso nocturno"
    "uso_prolongado" -> "Uso prolongado"
    "contacto_recurrente" -> "Contacto recurrente"
    else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun TutorAlertDto.isCritical(): Boolean = severidad.uppercase() in setOf("CRITICA", "CRÍTICA", "ALTA")

private fun severityColor(sev: String): Color = when (sev.uppercase()) {
    "CRITICA", "CRÍTICA" -> Oxblood
    "ALTA" -> Amber
    else -> Pine600
}

private fun TutorDeviceDto.vinculacionEstadoActivo(): Boolean =
    bindingState?.equals("ACTIVO", true) == true || estado.equals("ACTIVO", true)

private fun TutorDeviceDto.deviceLive(): Boolean {
    if (!vinculacionEstadoActivo()) return false
    val seen = lastSeen ?: return false
    return !isOlderThan(seen, 120)
}

private fun dayLabel(date: String): String = runCatching {
    java.time.LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE", Locale("es", "CL"))).take(3)
}.getOrDefault(date.takeLast(2))

private fun prettyTime(value: String?): String {
    if (value.isNullOrBlank()) return "sin registro"
    return runCatching {
        val instant = OffsetDateTime.parse(value).toInstant()
        DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale("es", "CL"))
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrElse { value.take(16).replace('T', ' ') }
}

private fun isOlderThan(value: String?, minutes: Long): Boolean = runCatching {
    val instant = OffsetDateTime.parse(value).toInstant()
    instant.isBefore(java.time.Instant.now().minusSeconds(minutes * 60))
}.getOrDefault(false)

private fun qrBitmap(payload: String, size: Int = 720): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}.getOrNull()
