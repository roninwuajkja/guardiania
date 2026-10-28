package cl.guardiania.agent.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cl.guardiania.agent.GuardianApp
import cl.guardiania.agent.data.NotPairedException
import cl.guardiania.agent.data.SessionRevokedException
import retrofit2.HttpException
import java.io.IOException

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as GuardianApp).container
        if (container.store.pairing() == null) return Result.success()
        return try {
            container.syncEngine.syncNow()
            Result.success()
        } catch (_: NotPairedException) {
            Result.success()
        } catch (_: SessionRevokedException) {
            Result.success()
        } catch (error: IOException) {
            Result.retry()
        } catch (error: HttpException) {
            if (error.code() == 429 || error.code() >= 500) Result.retry() else Result.success()
        } catch (error: Exception) {
            Result.retry()
        }
    }
}
