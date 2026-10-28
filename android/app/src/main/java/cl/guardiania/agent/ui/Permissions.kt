package cl.guardiania.agent.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class PermissionStatus(
    val usageAccess: Boolean,
    val notificationAccess: Boolean,
    val batteryUnrestricted: Boolean,
    val accessibility: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(usageAccess, notificationAccess, batteryUnrestricted).count { it }

    /** La accesibilidad es opcional y nunca bloquea la guardia base. */
    val allReady: Boolean
        get() = usageAccess && notificationAccess && batteryUnrestricted
}

fun readPermissionStatus(context: Context): PermissionStatus {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    val usageMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps?.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    } else {
        @Suppress("DEPRECATION")
        appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    }
    val power = context.getSystemService(PowerManager::class.java)
    val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    val accessibility = accessibilityManager
        ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        ?.any { service -> service.resolveInfo.serviceInfo.packageName == context.packageName } == true
    return PermissionStatus(
        usageAccess = usageMode == AppOpsManager.MODE_ALLOWED,
        notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName),
        batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
        accessibility = accessibility,
    )
}

@Composable
fun rememberPermissionStatus(): PermissionStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readPermissionStatus(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = readPermissionStatus(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return status
}

fun openUsageAccess(context: Context) {
    launchSettings(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}

fun openNotificationAccess(context: Context) {
    launchSettings(
        context,
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .putExtra(":settings:fragment_args_key", context.packageName),
    )
}

fun requestBatteryExemption(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    try {
        context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        launchSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

fun openAccessibilitySettings(context: Context) {
    launchSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun launchSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
