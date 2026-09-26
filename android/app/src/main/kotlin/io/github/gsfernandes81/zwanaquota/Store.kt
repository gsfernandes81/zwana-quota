package io.github.gsfernandes81.zwanaquota

import android.content.Context
import io.github.gsfernandes81.zwanaquota.core.Reading
import io.github.gsfernandes81.zwanaquota.core.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
    private val sessionFile = File(app.filesDir, "session.json")
    private val namesFile = File(app.filesDir, "names.json")

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

    /** The data session as last read, or null if it never has been. */
    fun session(): Session? = try {
        Session.fromJson(Json.parseToJsonElement(sessionFile.readText()))
    } catch (_: Exception) {
        null
    }

    fun save(session: Session) = replace(sessionFile, session.toJson().toString())

    fun forgetSession() {
        sessionFile.delete()
    }

    /**
     * Device names the network gave, by IP, with when each was asked. An
     * empty name is an answer too -- nobody answered -- so a device that
     * never will is not asked on every read.
     */
    fun names(): Map<String, Named> = try {
        Json.parseToJsonElement(namesFile.readText()).jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            Named(o.getValue("name").jsonPrimitive.content, o.getValue("at").jsonPrimitive.long)
        }
    } catch (_: Exception) {
        emptyMap()
    }

    fun saveNames(names: Map<String, Named>) = replace(
        namesFile,
        JsonObject(
            names.mapValues { (_, n) -> JsonObject(mapOf("name" to JsonPrimitive(n.name), "at" to JsonPrimitive(n.at))) },
        ).toString(),
    )

    class Named(val name: String, val at: Long)

    private fun replace(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    var watchEnabled: Boolean
        get() = prefs.getBoolean("watch", false)
        set(value) = prefs.edit().putBoolean("watch", value).apply()

    /**
     * Whether the watch may ask for a reading. Only means anything with
     * [watchEnabled] on; [WatchListener] runs only while both are.
     */
    var watchCanAsk: Boolean
        get() = prefs.getBoolean("watchCanAsk", false)
        set(value) = prefs.edit().putBoolean("watchCanAsk", value).apply()

    /** When the watch last asked, in epoch seconds: so a burst of presses is one read. */
    var lastAsk: Long
        get() = prefs.getLong("lastAsk", 0)
        set(value) = prefs.edit().putLong("lastAsk", value).apply()

    /** When the watch was last sent a reading, in epoch seconds; 0 for never. */
    var lastPush: Long
        get() = prefs.getLong("lastPush", 0)
        set(value) = prefs.edit().putLong("lastPush", value).apply()

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
