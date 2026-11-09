package cl.guardiania.agent.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Motion de producto: calmado, corto y sin rebotes. */
object GuardianMotion {
    const val fast = 120
    const val base = 180
    const val slow = 240
    const val chart = 320
    const val count = 320

    val standard = FastOutSlowInEasing
    val enter = FastOutSlowInEasing
    val exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

val LocalReducedMotion = staticCompositionLocalOf { false }

fun <T> motionTween(
    reduced: Boolean,
    durationMillis: Int,
    easing: androidx.compose.animation.core.Easing = GuardianMotion.standard,
): AnimationSpec<T> = if (reduced) snap() else tween(durationMillis, easing = easing)

/**
 * La escala de animación del sistema también cubre "quitar animaciones".
 * Se observa para que el cambio se aplique sin reiniciar la app.
 */
@Composable
fun rememberReducedMotionSetting(): Boolean {
    val context = LocalContext.current
    fun current(): Boolean = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

    var reduced by remember { mutableStateOf(current()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = current()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}
