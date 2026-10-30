package cl.guardiania.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun PairingScreen(
    baseUrl: String,
    busy: Boolean,
    error: String?,
    onVerifyQr: (String, String, Boolean) -> Unit,
    onVerifyCode: (String, String, Boolean) -> Unit,
    onClearError: () -> Unit,
) {
    var server by rememberSaveable(baseUrl) { mutableStateOf(baseUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var childAssent by rememberSaveable { mutableStateOf(false) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { if (childAssent) onVerifyQr(server, it, true) }
    }

    fun launchScanner() {
        if (!childAssent) return
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(QrScanActivity::class.java)
                .setPrompt("")
                .setBeepEnabled(false)
                // El retrato se fija en el manifiesto; pedirle a la librería que
                // "bloquee" la orientación la dejaba en horizontal.
                .setOrientationLocked(false),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Pine950),
    ) {
        PineBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 24.dp),
        ) {
            BrandLockup(dark = true)
            Spacer(Modifier.height(42.dp))
            Eyebrow("Vinculación · 10 minutos", color = Sage300.copy(alpha = 0.75f))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Vincular este teléfono.",
                color = Color(0xFFEAF2ED),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Hazlo junto al tutor. El teléfono analiza en local y envía solo señales derivadas.",
                color = Sage300.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))
            PairingStrip()
            Spacer(Modifier.height(26.dp))

            PaperCard {
                Column(Modifier.padding(20.dp)) {
                    Eyebrow("Paso 1 · conectar con el panel")
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = server,
                        onValueChange = {
                            server = it
                            onClearError()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Dirección del panel") },
                        supportingText = {
                            Text(
                                text = "Emulador: 10.0.2.2:8000 · Teléfono: la IP de tu computador.",
                                fontSize = 11.sp,
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Pine700,
                            focusedLabelColor = Pine700,
                        ),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = childAssent,
                            onCheckedChange = { childAssent = it },
                            colors = CheckboxDefaults.colors(checkedColor = Pine700),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = "Entiendo, junto a mi tutor/a, qué señales se comparten y acepto vincular este teléfono. Puedo pedir que se detenga.",
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 10.dp),
                            color = Ink2,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = ::launchScanner,
                        enabled = !busy && childAssent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.size(9.dp))
                        Text("Escanear el QR del panel", style = MaterialTheme.typography.labelLarge)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HorizontalDivider(Modifier.weight(1f), color = Line)
                        Text(
                            text = "o usa el código manual",
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = Muted,
                            fontFamily = Mono,
                            fontSize = 9.5.sp,
                            letterSpacing = 0.8.sp,
                        )
                        HorizontalDivider(Modifier.weight(1f), color = Line)
                    }

                    OutlinedTextField(
                        value = code,
                        onValueChange = { raw ->
                            code = raw.filter(Char::isDigit).take(6)
                            onClearError()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Código de 6 dígitos") },
                        placeholder = {
                            Text(
                                text = "000 000",
                                color = Muted.copy(alpha = 0.5f),
                                fontFamily = Mono,
                                letterSpacing = 6.sp,
                            )
                        },
                        textStyle = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = Mono,
                            letterSpacing = 6.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Pine700,
                            focusedLabelColor = Pine700,
                        ),
                    )

                    if (error != null) {
                        Spacer(Modifier.height(14.dp))
                        InlineError(error)
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { onVerifyCode(server, code, true) },
                        enabled = !busy && code.length == 6 && childAssent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Pine700),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(17.dp),
                                strokeWidth = 2.dp,
                                color = Pine700,
                            )
                            Spacer(Modifier.size(9.dp))
                            Text("Verificando…")
                        } else {
                            Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(9.dp))
                            Text("Vincular con el código")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    PrivacyLine(
                        "Se comparten categorías, minutos y conteos. Nunca textos, fotos ni contactos.",
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                text = "1 · Vinculas  ·  2 · Analiza en local  ·  3 · Recibes señales",
                modifier = Modifier.fillMaxWidth(),
                color = Sage300.copy(alpha = 0.55f),
                fontFamily = Mono,
                fontSize = 9.5.sp,
                letterSpacing = 0.6.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PairingStrip() {
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(30.dp),
        ) {
            val nightWidth = size.width * 0.25f
            drawRect(
                color = Sage300.copy(alpha = 0.07f),
                topLeft = Offset.Zero,
                size = Size(nightWidth, size.height),
            )
            val step = size.width / 24f
            for (hour in 0..24 step 2) {
                val x = hour * step
                drawLine(
                    color = Sage300.copy(alpha = 0.14f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
            drawLine(
                color = Sage300.copy(alpha = 0.28f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.5f,
            )
            drawLine(
                color = Sage300.copy(alpha = 0.9f),
                start = Offset(step * 15.2f, size.height * 0.18f),
                end = Offset(step * 15.2f, size.height * 0.92f),
                strokeWidth = 4f,
            )
            drawLine(
                color = Color(0xFFD9A15B),
                start = Offset(step * 20.1f, size.height * 0.08f),
                end = Offset(step * 20.1f, size.height * 0.92f),
                strokeWidth = 4f,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("00", "06", "12", "18", "24").forEach { hour ->
                Text(
                    text = hour,
                    color = Sage300.copy(alpha = 0.5f),
                    fontFamily = Mono,
                    fontSize = 9.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Una guardia de 24 horas, no una grabación.",
            color = Sage300.copy(alpha = 0.6f),
            fontFamily = Mono,
            fontSize = 9.5.sp,
            letterSpacing = 0.5.sp,
        )
    }
}
