package cl.guardiania.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GuardianMark(
    size: Dp = 40.dp,
    tint: Color = Color.White,
    accent: Color = Sage300,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val s = minOf(this.size.width, this.size.height)
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val ringRadius = s * 0.344f
        val ringStroke = s * 0.109f

        // Vínculo: dos órbitas que se cuidan, con el punto libre y su satélite.
        drawArc(
            color = tint,
            startAngle = 315f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(cx - ringRadius, cy - ringRadius),
            size = Size(ringRadius * 2f, ringRadius * 2f),
            style = Stroke(width = ringStroke, cap = StrokeCap.Round),
        )
        drawCircle(tint, radius = s * 0.078f, center = Offset(cx - s * 0.102f, cy - s * 0.102f))
        drawCircle(accent, radius = s * 0.051f, center = Offset(cx + s * 0.383f, cy + s * 0.383f))
    }
}

@Composable
fun BrandLockup(dark: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GuardianMark(
            size = 40.dp,
            tint = if (dark) Color.White else Pine900,
            accent = if (dark) Sage300 else Sage500,
        )
        Spacer(Modifier.size(11.dp))
        Column {
            Text(
                text = "IA Guardian",
                color = if (dark) Color(0xFFEAF2ED) else Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            )
            Text(
                text = "PRIVACIDAD POR DISEÑO",
                color = if (dark) Sage300.copy(alpha = 0.8f) else Muted,
                fontFamily = Mono,
                fontSize = 8.5.sp,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

@Composable
fun PineBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Pine950)
            .drawBehind {
                val step = 80.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = Sage300.copy(alpha = 0.055f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                    x += step
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Sage500.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.98f),
                        radius = size.minDimension * 0.9f,
                    ),
                    radius = size.minDimension * 0.9f,
                    center = Offset(size.width * 0.92f, size.height * 0.98f),
                )
            },
    )
}

@Composable
fun Eyebrow(
    text: String,
    color: Color = Muted,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = Mono,
        fontSize = 9.5.sp,
        letterSpacing = 1.5.sp,
        lineHeight = 14.sp,
    )
}

@Composable
fun Pill(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
) {
    Row(
        modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dotColor != null) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(dotColor, RoundedCornerShape(999.dp)),
            )
        }
        Text(
            text = text.uppercase(),
            color = foreground,
            fontFamily = Mono,
            fontSize = 9.sp,
            letterSpacing = 1.1.sp,
        )
    }
}

@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(18.dp)),
        color = Card,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
fun PrivacyLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = Muted,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(15.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
fun InlineError(text: String) {
    Surface(
        color = OxbloodSoft,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = OxbloodInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
fun CenteredNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = Muted,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))
