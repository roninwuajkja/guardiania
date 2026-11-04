package cl.guardiania.agent.data

import android.content.ContentValues
import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase as AndroidSqlite
import net.sqlcipher.database.SQLiteDatabase
import java.io.File
import java.util.UUID

data class PairingRecord(
    val baseUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val bindingId: String,
    val deviceAlias: String,
    val tutorAlias: String,
    val linkedAt: Long,
)

data class TutorSessionRecord(
    val baseUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val tutorId: String,
    val email: String,
    val alias: String,
    val emailVerified: Boolean,
    val expiresAt: Long,
)

data class LocalSignal(
    val id: Long,
    val category: String,
    val flags: List<String>,
    val createdAt: Long,
)

data class QueuedCapture(
    val eventId: String,
    val payload: String,
    val createdAt: Long,
)

data class ComposedCapture(
    val eventId: String,
    val payload: String,
    val signalIds: List<Long>,
)

/**
 * Encrypted store for pairing credentials, the local signal inbox and the
 * sync outbox. Only derived features are written: never notification text,
 * titles, photos or contact names.
 */
class SecureStore private constructor(
    private val file: File,
    private val passphrase: String,
) {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, passphrase, null)

    init {
        ensureSchema()
    }

    private fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pairing (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                base_url TEXT NOT NULL,
                access_token TEXT NOT NULL,
                refresh_token TEXT NOT NULL,
                device_id TEXT NOT NULL,
                binding_id TEXT NOT NULL,
                device_alias TEXT NOT NULL,
                tutor_alias TEXT NOT NULL,
                linked_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tutor_session (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                base_url TEXT NOT NULL,
                access_token TEXT NOT NULL,
                refresh_token TEXT NOT NULL,
                tutor_id TEXT NOT NULL,
                email TEXT NOT NULL,
                alias TEXT NOT NULL,
                email_verified INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS signals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                flags TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS outbox (
                event_id TEXT PRIMARY KEY,
                payload TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_signals_created ON signals(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_outbox_created ON outbox(created_at)")
    }

    @Synchronized
    fun installId(): String {
        meta("install_id")?.let { return it }
        val generated = UUID.randomUUID().toString()
        setMeta("install_id", generated)
        return generated
    }

    @Synchronized
    fun savePairing(record: PairingRecord) {
        val values = ContentValues().apply {
            put("id", 1)
            put("base_url", record.baseUrl)
            put("access_token", record.accessToken)
            put("refresh_token", record.refreshToken)
            put("device_id", record.deviceId)
            put("binding_id", record.bindingId)
            put("device_alias", record.deviceAlias)
            put("tutor_alias", record.tutorAlias)
            put("linked_at", record.linkedAt)
        }
        db.insertWithOnConflict("pairing", null, values, AndroidSqlite.CONFLICT_REPLACE)
        setMeta("base_url_hint", record.baseUrl)
    }

    @Synchronized
    fun saveTutorSession(record: TutorSessionRecord) {
        val values = ContentValues().apply {
            put("id", 1)
            put("base_url", record.baseUrl)
            put("access_token", record.accessToken)
            put("refresh_token", record.refreshToken)
            put("tutor_id", record.tutorId)
            put("email", record.email)
            put("alias", record.alias)
            put("email_verified", if (record.emailVerified) 1 else 0)
            put("expires_at", record.expiresAt)
        }
        db.insertWithOnConflict("tutor_session", null, values, AndroidSqlite.CONFLICT_REPLACE)
        setMeta("base_url_hint", record.baseUrl)
    }

    @Synchronized
    fun tutorSession(): TutorSessionRecord? {
        db.rawQuery(
            "SELECT base_url, access_token, refresh_token, tutor_id, email, alias, email_verified, expires_at FROM tutor_session WHERE id = 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return TutorSessionRecord(
                baseUrl = cursor.getString(0),
                accessToken = cursor.getString(1),
                refreshToken = cursor.getString(2),
                tutorId = cursor.getString(3),
                email = cursor.getString(4),
                alias = cursor.getString(5),
                emailVerified = cursor.getInt(6) == 1,
                expiresAt = cursor.getLong(7),
            )
        }
    }

    @Synchronized
    fun updateTutorTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        val values = ContentValues().apply {
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            put("expires_at", expiresAt)
        }
        db.update("tutor_session", values, "id = 1", null)
    }

    @Synchronized
    fun clearTutorSession() {
        db.delete("tutor_session", null, null)
    }

    @Synchronized
    fun pairing(): PairingRecord? {
        db.rawQuery(
            "SELECT base_url, access_token, refresh_token, device_id, binding_id, device_alias, tutor_alias, linked_at FROM pairing WHERE id = 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return PairingRecord(
                baseUrl = cursor.getString(0),
                accessToken = cursor.getString(1),
                refreshToken = cursor.getString(2),
                deviceId = cursor.getString(3),
                bindingId = cursor.getString(4),
                deviceAlias = cursor.getString(5),
                tutorAlias = cursor.getString(6),
                linkedAt = cursor.getLong(7),
            )
        }
    }

    @Synchronized
    fun updateAccessToken(accessToken: String) {
        val values = ContentValues().apply { put("access_token", accessToken) }
        db.update("pairing", values, "id = 1", null)
    }

    @Synchronized
    fun clearPairing() {
        db.beginTransaction()
        try {
            db.delete("pairing", null, null)
            db.delete("signals", null, null)
            db.delete("outbox", null, null)
            db.delete("meta", "key IN ('last_sync_at', 'usage_hour')", null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun addSignal(category: String, flags: List<String>, createdAt: Long) {
        val values = ContentValues().apply {
            put("category", category)
            put("flags", flags.joinToString(","))
            put("created_at", createdAt)
        }
        db.insertOrThrow("signals", null, values)
        db.execSQL(
            "DELETE FROM signals WHERE id NOT IN (SELECT id FROM signals ORDER BY id DESC LIMIT $MAX_SIGNALS)",
        )
    }

    @Synchronized
    fun pendingSignals(limit: Int): List<LocalSignal> {
        db.rawQuery(
            "SELECT id, category, flags, created_at FROM signals ORDER BY created_at ASC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            val items = mutableListOf<LocalSignal>()
            while (cursor.moveToNext()) {
                items += LocalSignal(
                    id = cursor.getLong(0),
                    category = cursor.getString(1),
                    flags = cursor.getString(2).split(',').filter(String::isNotBlank),
                    createdAt = cursor.getLong(3),
                )
            }
            return items
        }
    }

    /** Inserts the composed captures and drains their source signals in one commit. */
    @Synchronized
    fun commitComposed(captures: List<ComposedCapture>) {
        if (captures.isEmpty()) return
        db.beginTransaction()
        try {
            captures.forEach { capture ->
                val values = ContentValues().apply {
                    put("event_id", capture.eventId)
                    put("payload", capture.payload)
                    put("created_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("outbox", null, values, AndroidSqlite.CONFLICT_IGNORE)
                if (capture.signalIds.isNotEmpty()) {
                    val placeholders = capture.signalIds.joinToString(",") { "?" }
                    db.delete(
                        "signals",
                        "id IN ($placeholders)",
                        capture.signalIds.map { it.toString() }.toTypedArray(),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun pruneSignals(cutoff: Long) {
        db.delete("signals", "created_at < ?", arrayOf(cutoff.toString()))
    }

    @Synchronized
    fun enqueueCapture(eventId: String, payload: String, createdAt: Long) {
        val values = ContentValues().apply {
            put("event_id", eventId)
            put("payload", payload)
            put("created_at", createdAt)
        }
        db.insertWithOnConflict("outbox", null, values, AndroidSqlite.CONFLICT_IGNORE)
    }

    @Synchronized
    fun pendingCaptures(limit: Int): List<QueuedCapture> {
        db.rawQuery(
            "SELECT event_id, payload, created_at FROM outbox ORDER BY created_at ASC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            val items = mutableListOf<QueuedCapture>()
            while (cursor.moveToNext()) {
                items += QueuedCapture(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
            }
            return items
        }
    }

    @Synchronized
    fun dropCapture(eventId: String) {
        db.delete("outbox", "event_id = ?", arrayOf(eventId))
    }

    @Synchronized
    fun markCapturesSynced(eventIds: List<String>) {
        if (eventIds.isEmpty()) return
        val placeholders = eventIds.joinToString(",") { "?" }
        db.delete("outbox", "event_id IN ($placeholders)", eventIds.toTypedArray())
    }

    @Synchronized
    fun pendingCount(): Int = count("signals") + count("outbox")

    @Synchronized
    fun lastSyncAt(): Long? = meta("last_sync_at")?.toLongOrNull()

    @Synchronized
    fun setLastSyncAt(value: Long) = setMeta("last_sync_at", value.toString())

    /** Avanza la marca de última notificación sin retroceder ante escrituras concurrentes. */
    @Synchronized
    fun advanceLastNotification(at: Long) {
        val previous = meta("last_notification_at")?.toLongOrNull() ?: 0L
        if (at > previous) setMeta("last_notification_at", at.toString())
    }

    @Synchronized
    fun usageHour(): String? = meta("usage_hour")

    @Synchronized
    fun setUsageHour(value: String) = setMeta("usage_hour", value)

    @Synchronized
    fun meta(key: String): String? {
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    @Synchronized
    fun setMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict("meta", null, values, AndroidSqlite.CONFLICT_REPLACE)
    }

    private fun count(table: String): Int {
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    companion object {
        private const val DB_NAME = "guardiania.db"
        private const val MAX_SIGNALS = 4000

        fun open(context: Context, passphrase: String): SecureStore {
            SQLiteDatabase.loadLibs(context)
            val file = context.getDatabasePath(DB_NAME)
            file.parentFile?.mkdirs()
            return try {
                SecureStore(file, passphrase)
            } catch (error: SQLException) {
                // A corrupted or undecryptable store must not brick the agent.
                file.delete()
                SecureStore(file, passphrase)
            }
        }
    }
}
