package io.github.gsfernandes81.zwanaquota

import android.content.Context
import io.github.gsfernandes81.zwanaquota.core.Reading
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What the app keeps between runs: the last reading, the switches, and the
 * notes the settings screen shows -- which is the only window anyone has into
 * this app, since nobody watching it has logcat.
 *
 * Nothing secret is here. The login and the session cookie are in [Vault].
 */
class Store(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("zwana", Context.MODE_PRIVATE)
    private val cache = File(app.filesDir, "reading.json")
    private val journal = File(app.filesDir, "journal.txt")

    /** The last reading of any age, or null -- an unreadable cache is no reading. */
    fun reading(): Reading? = try {
        Reading.fromJson(Json.parseToJsonElement(cache.readText()))
    } catch (_: Exception) {
        null
    }

    /** Written whole and renamed into place, so a reader never sees half of one. */
    fun save(reading: Reading) {
        val tmp = File(cache.path + ".tmp")
        tmp.writeText(reading.toJson().toString())
        tmp.renameTo(cache)
    }

    var watchEnabled: Boolean
        get() = prefs.getBoolean("watch", false)
        set(value) = prefs.edit().putBoolean("watch", value).apply()

    /** A counter the watch shows, so a message can be told from the one before it. */
    @Synchronized
    fun nextSequence(): Int {
        val n = prefs.getInt("sequence", 0) + 1
        prefs.edit().putInt("sequence", n).apply()
        return n
    }

    /** Record the latest word on one subject, and add it to the journal. */
    fun note(subject: String, text: String) {
        val line = "${stamp()} $text"
        prefs.edit().putString("note.$subject", line).apply()
        log("$subject: $text")
    }

    fun notes(): Map<String, String> = prefs.all
        .filterKeys { it.startsWith("note.") }
        .map { (k, v) -> k.removePrefix("note.") to v.toString() }
        .toMap(sortedMapOf())

    @Synchronized
    fun log(text: String) {
        val lines = (if (journal.exists()) journal.readLines() else emptyList()) + "${stamp()} $text"
        journal.writeText(lines.takeLast(JOURNAL_LINES).joinToString("\n", postfix = "\n"))
    }

    fun journal(): String = if (journal.exists()) journal.readText() else ""

    private fun stamp(): String = LocalDateTime.now().format(STAMP)

    companion object {
        private const val JOURNAL_LINES = 300
        private val STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}
