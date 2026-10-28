package cl.guardiania.agent.capture

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Screen-time minutes for a window, computed from usage events. The result is
 * an aggregate: no app names, titles or screen contents leave this class.
 */
class UsageCollector(private val context: Context) {

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun minutesBetween(start: Long, end: Long): Int {
        if (!hasAccess() || end <= start) return 0
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return 0
        val events = manager.queryEvents(start, end) ?: return 0
        val open = mutableMapOf<String, Long>()
        var total = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            if (packageName == context.packageName || packageName == SYSTEM_UI) continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    open.putIfAbsent(packageName, event.timeStamp)
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    open.remove(packageName)?.let { since ->
                        total += (event.timeStamp - since).coerceAtLeast(0)
                    }
                }
            }
        }
        open.values.forEach { since ->
            total += (end - since).coerceAtLeast(0)
        }
        return (total / 60_000L).toInt().coerceIn(0, 1440)
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
