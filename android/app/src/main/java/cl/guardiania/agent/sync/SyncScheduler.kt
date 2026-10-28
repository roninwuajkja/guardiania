package cl.guardiania.agent.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import android.os.Build
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val UNIQUE_NAME = "guardiania-periodic-sync"
    private const val PRIORITY_NAME = "guardiania-priority-sync"

    private fun networkConstraint(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Pide una sincronización casi inmediata cuando se detecta una señal
     * MEDIA/ALTA. En Android 12+ se marca como expedited; en versiones
     * anteriores WorkManager la ejecuta como trabajo único normal. El sistema
     * puede demorarla por batería o cuota, por eso no se promete SLA desde el
     * evento del menor: el SLA de 3 s cubre evaluación → notificación.
     */
    fun requestPrioritySync(context: Context) {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PRIORITY_NAME, ExistingWorkPolicy.REPLACE, builder.build())
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(UNIQUE_NAME)
        manager.cancelUniqueWork(PRIORITY_NAME)
    }
}
