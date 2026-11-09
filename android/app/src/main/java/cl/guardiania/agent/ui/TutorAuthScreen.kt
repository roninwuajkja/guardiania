package cl.guardiania.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.guardiania.agent.TutorAuthMode

@Composable
fun TutorAuthScreen(
    baseUrl: String,
    mode: TutorAuthMode,
    busy: Boolean,
    error: String?,
    registrationPending: String?,
    onModeChange: (TutorAuthMode) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onLogin: (String, String, String) -> Unit,
    onRegister: (String, String, String, String) -> Unit,
    onClearPending: () -> Unit,
) {
    var server by rememberSaveable(baseUrl) { mutableStateOf(baseUrl) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var alias by rememberSaveable { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val legalBase = server.trim().trimEnd('/')
    val registering = mode == TutorAuthMode.REGISTER
    val reducedMotion = LocalReducedMotion.current

    fun submit() {
        localError = null
        if (email.isBlank() || !email.contains('@')) {
            localError = "Escribe un correo válido."
            return
        }
        if (password.length < 12) {
            localError = "La contraseña debe tener al menos 12 caracteres."
            return
        }
        if (registering) {
            if (alias.isBlank()) {
                localError = "Escribe un alias para el panel."
                return
            }
            if (!consent) {
                localError = "Debes aceptar los términos, la privacidad y declarar la tutoría."
                return
            }
            onRegister(server, email, password, alias)
        } else {
            onLogin(server, email, password)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Pine950)
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                GuardianMark(size = 38.dp, tint = Sage300, accent = Sage300)
                Spacer(Modifier.height(20.dp))
                Text(
                    text = if (registering) "Crea tu cuenta de tutor." else "Entra a tu panel.",
                    color = Color(0xFFEAF2ED),
                    style = MaterialTheme.typography.displaySmall,
                    lineHeight = 42.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Gestiona señales, alertas y dispositivos sin leer conversaciones.",
                    color = Sage300.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Column(Modifier.padding(22.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Card2, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ModeTab("Entrar", !registering, Modifier.weight(1f)) {
                        onClearPending()
                        onModeChange(TutorAuthMode.LOGIN)
                    }
                    ModeTab("Crear cuenta", registering, Modifier.weight(1f)) {
                        onClearPending()
                        onModeChange(TutorAuthMode.REGISTER)
                    }
                }
                Spacer(Modifier.height(18.dp))

                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; localError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Dirección del panel") },
                    supportingText = { Text("Panel desplegado. En local: http://10.0.2.2:8000.", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; localError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Correo") },
                    leadingIcon = { Icon(Icons.Outlined.Mail, contentDescription = null, modifier = Modifier.size(19.dp)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; localError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (registering) "Contraseña nueva (mínimo 12)" else "Contraseña") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(19.dp)) },
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (reveal) "Ocultar contraseña" else "Mostrar contraseña",
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                )

                AnimatedVisibility(
                    visible = registering,
                    enter = if (reducedMotion) {
                        EnterTransition.None
                    } else {
                        fadeIn(tween(180)) + expandVertically(tween(220))
                    },
                    exit = if (reducedMotion) {
                        ExitTransition.None
                    } else {
                        fadeOut(tween(120)) + shrinkVertically(tween(180))
                    },
                ) {
                    Column {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it; localError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Alias visible (ej. Camila R.)") },
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(19.dp)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = consent,
                            onCheckedChange = { consent = it; onConsentChange(it) },
                            colors = CheckboxDefaults.colors(checkedColor = Pine700),
                        )
                        Text(
                            text = "Acepto los términos y la política de privacidad, y declaro que ejerzo la patria potestad, tutela o cuidado personal del menor.",
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 10.dp),
                            color = Ink2,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    }
                }

                val shownError = localError ?: error
                if (shownError != null) {
                    Spacer(Modifier.height(12.dp))
                    SurfaceError(shownError)
                }
                if (registrationPending != null) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFEAF5EE), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    ) {
                        Text(registrationPending, color = Color(0xFF1C5A3D), fontSize = 12.5.sp, lineHeight = 18.sp)
                    }
                }

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = ::submit,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.size(9.dp))
                        Text("Conectando…")
                    } else {
                        Text(if (registering) "Crear cuenta" else "Entrar al panel", fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Nunca pediremos la contraseña del correo ni acceso a tus conversaciones.",
                    color = Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                )
                TextButton(
                    onClick = { runCatching { uriHandler.openUri("$legalBase/recuperar") } },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                ) { Text("Recuperar contraseña", fontSize = 11.5.sp) }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "privacidad" to "Privacidad",
                        "terminos" to "Términos",
                        "consentimiento" to "Consentimiento",
                        "seguridad" to "Seguridad",
                    ).forEach { (path, label) ->
                        TextButton(
                            onClick = { runCatching { uriHandler.openUri("$legalBase/legal/$path") } },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        ) { Text(label, fontSize = 11.sp) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(if (selected) Card else Color.Transparent, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Ink else Muted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SurfaceError(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFBEEEB), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(message, color = Color(0xFF8A2C1A), fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Pine700,
    focusedLabelColor = Pine700,
)
