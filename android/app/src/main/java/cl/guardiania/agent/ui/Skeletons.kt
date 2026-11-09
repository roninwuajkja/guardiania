package cl.guardiania.agent.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bloques de carga con la misma silueta que el contenido real.
 * El pulso es lento y se apaga con la escala de animación del sistema.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    Box(modifier.background(Line.copy(alpha = alpha), shape))
}

@Composable
fun SkeletonLine(
    width: Dp? = null,
    height: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    SkeletonBlock(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height),
        shape = RoundedCornerShape(999.dp),
    )
}

@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Card,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun SkeletonSectionHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SkeletonLine(width = 130.dp, height = 14.dp)
        Spacer(Modifier.weight(1f))
        SkeletonLine(width = 52.dp, height = 10.dp)
    }
}

@Composable
fun SkeletonAlertCard(modifier: Modifier = Modifier) {
    SkeletonCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonLine(width = 62.dp, height = 8.dp)
            Spacer(Modifier.width(8.dp))
            SkeletonLine(width = 48.dp, height = 8.dp)
        }
        Spacer(Modifier.height(10.dp))
        SkeletonLine(width = 210.dp, height = 15.dp)
        Spacer(Modifier.height(8.dp))
        SkeletonLine(height = 10.dp)
        Spacer(Modifier.height(5.dp))
        SkeletonLine(width = 240.dp, height = 10.dp)
        Spacer(Modifier.height(12.dp))
        SkeletonBlock(
            modifier = Modifier.width(76.dp).height(30.dp),
            shape = RoundedCornerShape(8.dp),
        )
    }
}

@Composable
fun SkeletonDeviceCard(modifier: Modifier = Modifier) {
    SkeletonCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBlock(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                SkeletonLine(width = 120.dp, height = 13.dp)
                Spacer(Modifier.height(7.dp))
                SkeletonLine(width = 160.dp, height = 9.dp)
            }
            SkeletonLine(width = 54.dp, height = 9.dp)
        }
        Spacer(Modifier.height(12.dp))
        SkeletonLine(width = 130.dp, height = 9.dp)
        Spacer(Modifier.height(7.dp))
        SkeletonLine(width = 210.dp, height = 9.dp)
    }
}

@Composable
fun TutorHomeSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SkeletonCard(Modifier.fillMaxWidth()) {
            SkeletonLine(width = 86.dp, height = 9.dp)
            Spacer(Modifier.height(12.dp))
            SkeletonLine(width = 240.dp, height = 22.dp)
            Spacer(Modifier.height(6.dp))
            SkeletonLine(width = 200.dp, height = 22.dp)
            Spacer(Modifier.height(10.dp))
            SkeletonLine(width = 250.dp, height = 10.dp)
            Spacer(Modifier.height(14.dp))
            SkeletonBlock(
                modifier = Modifier.width(150.dp).height(40.dp),
                shape = RoundedCornerShape(10.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            repeat(3) {
                SkeletonCard(Modifier.weight(1f)) {
                    SkeletonLine(width = 46.dp, height = 8.dp)
                    Spacer(Modifier.height(10.dp))
                    SkeletonLine(width = 36.dp, height = 24.dp)
                    Spacer(Modifier.height(9.dp))
                    SkeletonLine(width = 62.dp, height = 8.dp)
                }
            }
        }
        SkeletonCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SkeletonLine(width = 120.dp, height = 13.dp)
                Spacer(Modifier.weight(1f))
                SkeletonLine(width = 64.dp, height = 9.dp)
            }
            Spacer(Modifier.height(14.dp))
            SkeletonBlock(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                shape = RoundedCornerShape(10.dp),
            )
        }
        SkeletonSectionHeader()
        SkeletonAlertCard(Modifier.fillMaxWidth())
        SkeletonAlertCard(Modifier.fillMaxWidth())
        SkeletonSectionHeader()
        SkeletonDeviceCard(Modifier.fillMaxWidth())
        SkeletonDeviceCard(Modifier.fillMaxWidth())
    }
}

@Composable
fun TutorAlertsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            SkeletonAlertCard(Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun TutorDevicesSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            SkeletonDeviceCard(Modifier.fillMaxWidth())
        }
    }
}
