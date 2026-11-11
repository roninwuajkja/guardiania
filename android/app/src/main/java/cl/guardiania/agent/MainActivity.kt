package cl.guardiania.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.guardiania.agent.notifications.TutorNotifications
import cl.guardiania.agent.ui.GuardianMark
import cl.guardiania.agent.ui.GuardianTheme
import cl.guardiania.agent.ui.PairingScreen
import cl.guardiania.agent.ui.Paper
import cl.guardiania.agent.ui.Pine950
import cl.guardiania.agent.ui.RoleChooserScreen
import cl.guardiania.agent.ui.Sage300
import cl.guardiania.agent.ui.StatusScreen
import cl.guardiania.agent.ui.TutorAuthScreen
import cl.guardiania.agent.ui.TutorShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    /** Se activa si la actividad nace (o recibe un intent) desde una alerta push. */
    private var openAlerts by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openAlerts = intent?.getBooleanExtra(TutorNotifications.EXTRA_OPEN_ALERTS, false) == true ||
            intent?.getStringExtra("open_alerts") == "1"
        setContent {
            GuardianTheme {
                GuardianAgentApp(
                    openAlerts = openAlerts,
                    onOpenAlertsHandled = { openAlerts = false },
                )
            }
        }
    }

    /**
     * Con la actividad viva en segundo plano, tocar la notificación llega aquí
     * (no a `onCreate`); sin esto el panel se abriría en Inicio.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(TutorNotifications.EXTRA_OPEN_ALERTS, false) ||
            intent.getStringExtra("open_alerts") == "1"
        ) {
            intent.removeExtra(TutorNotifications.EXTRA_OPEN_ALERTS)
            openAlerts = true
        }
    }
}

/**
 * Hands the tutor's JSON export to the system share sheet (Files, Drive, email…).
 */
private fun shareExport(context: android.content.Context, json: String) {
    runCatching {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "guardiania-export-$stamp.json")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(context, "cl.guardiania.agent.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir exportación"))
    }
}

@Composable
private fun GuardianAgentApp(
    openAlerts: Boolean,
    onOpenAlertsHandled: () -> Unit,
    vm: MainViewModel = viewModel(),
    tutorVm: TutorViewModel = viewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val tutorState by tutorVm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val askedNotifications = remember { AtomicBoolean(false) }

    LaunchedEffect(tutorState.session?.tutorId) {
        val hasSession = tutorState.session != null
        if (hasSession &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED &&
            askedNotifications.compareAndSet(false, true)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // El stream del tutor vive solo mientras la app está en primer plano.
    // En segundo plano las alertas llegan por FCM.
    DisposableEffect(lifecycleOwner, tutorState.session?.tutorId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    if (tutorState.session != null) {
                        tutorVm.startRealtime()
                        tutorVm.refreshIfStale()
                    }
                Lifecycle.Event.ON_STOP -> tutorVm.stopRealtime()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            tutorState.session != null
        ) {
            tutorVm.startRealtime()
            tutorVm.refreshIfStale()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tutorVm.stopRealtime()
        }
    }

    LaunchedEffect(openAlerts, tutorState.session?.tutorId) {
        val activity = context as? ComponentActivity
        if (openAlerts && tutorState.session != null) {
            tutorVm.selectTab(TutorTab.ALERTAS)
            activity?.intent?.removeExtra(TutorNotifications.EXTRA_OPEN_ALERTS)
            onOpenAlertsHandled()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbar.showSnackbar(message)
            vm.clearMessage()
        }
    }
    LaunchedEffect(tutorState.message) {
        tutorState.message?.let { message ->
            snackbar.showSnackbar(message)
            tutorVm.clearMessage()
        }
    }
    LaunchedEffect(tutorState.error) {
        tutorState.error?.let { message ->
            snackbar.showSnackbar(message)
            tutorVm.clearError()
        }
    }
    LaunchedEffect(tutorState.session?.tutorId) {
        vm.setTutorSessionAvailable(tutorState.session != null)
    }

    fun openLegal(path: String) {
        val base = tutorState.baseUrl.trim().trimEnd('/')
        runCatching { uriHandler.openUri("$base/legal/$path") }
    }

    Scaffold(
        containerColor = Paper,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !state.ready -> Splash()

                state.role == null -> RoleChooserScreen(
                    hasTutorSession = state.hasTutorSession,
                    hasPairing = state.pairing != null,
                    baseUrl = state.baseUrl,
                    onTutor = { vm.chooseRole(AppRole.TUTOR) },
                    onChild = { vm.chooseRole(AppRole.CHILD) },
                )

                state.role == AppRole.TUTOR -> {
                    if (!tutorState.ready) {
                        Splash()
                    } else if (tutorState.session == null) {
                        TutorAuthScreen(
                            baseUrl = tutorState.baseUrl,
                            mode = tutorState.authMode,
                            busy = tutorState.busy,
                            error = tutorState.error,
                            registrationPending = tutorState.registrationPending,
                            consentChecked = tutorState.consentChecked,
                            guardianConsentChecked = tutorState.guardianConsentChecked,
                            onModeChange = tutorVm::setAuthMode,
                            onConsentChange = tutorVm::setConsent,
                            onGuardianConsentChange = tutorVm::setGuardianConsent,
                            onLogin = tutorVm::login,
                            onRegister = tutorVm::register,
                            onClearPending = tutorVm::clearRegistrationPending,
                        )
                    } else {
                        TutorShell(
                            state = tutorState,
                            onRefresh = tutorVm::refresh,
                            onTab = tutorVm::selectTab,
                            onAlertState = tutorVm::updateAlert,
                            onRevoke = tutorVm::revokeDevice,
                            onSetUnlinkCode = tutorVm::setUnlinkCode,
                            onClearUnlinkCode = tutorVm::clearUnlinkCode,
                            onGeneratePairing = tutorVm::generatePairing,
                            onCancelPairing = tutorVm::cancelPairing,
                            onClearPairing = tutorVm::clearPairing,
                            onLogout = tutorVm::logout,
                            onLogoutAll = tutorVm::logoutAll,
                            onSwitchToChild = { vm.chooseRole(AppRole.CHILD) },
                            onOpenLegal = ::openLegal,
                            onResendVerification = tutorVm::resendVerification,
                            onChangePassword = tutorVm::changePassword,
                            onExport = { tutorVm.export(onReady = { shareExport(context, it) }) },
                            onTestPush = tutorVm::testPush,
                        )
                    }
                }

                state.pairing == null -> PairingScreen(
                    baseUrl = state.baseUrl,
                    busy = state.busy,
                    error = state.error,
                    onVerifyQr = vm::verifyWithQr,
                    onVerifyCode = vm::verifyWithCode,
                    onClearError = vm::clearError,
                )

                else -> StatusScreen(
                    state = state,
                    onSync = vm::syncNow,
                    onTestSignal = vm::sendTestSignal,
                    onUnpair = vm::unpair,
                    onCancelUnlinkPin = vm::cancelUnlinkPin,
                    onSwitchToTutor = { vm.chooseRole(AppRole.TUTOR) },
                )
            }

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Pine950),
            )
        }
    }
}

@Composable
private fun Splash() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Pine950),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GuardianMark(size = 68.dp, tint = Sage300, accent = Sage300)
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Sage300,
                strokeWidth = 2.dp,
            )
        }
    }
}
