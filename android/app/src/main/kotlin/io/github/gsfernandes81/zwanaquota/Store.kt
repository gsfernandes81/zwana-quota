package io.github.gsfernandes81.zwanaquota

import android.content.Context
import io.github.gsfernandes81.zwanaquota.core.Answers
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

    /**
     * Whether the watch may also switch data and take devices off. Only
     * means anything with [watchCanAsk] on: the listener is how it asks.
     */
    var watchCanControl: Boolean
        get() = prefs.getBoolean("watchCanControl", false)
        set(value) = prefs.edit().putBoolean("watchCanControl", value).apply()

    /** The MAC of each device the watch was last offered to take off, by IP. */
    var offeredMacs: Map<String, String>
        get() = prefs.getString("offeredMacs", null)?.lines()?.mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }?.toMap().orEmpty()
        set(value) = prefs.edit().putString("offeredMacs", value.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()

    /**
     * The `sent` stamp of the last message made for the watch that carried a
     * run's reading, in epoch seconds (Refresher.push); 0 for never.
     */
    var lastPush: Long
        get() = prefs.getLong("lastPush", 0)
        set(value) = prefs.edit().putLong("lastPush", value).apply()

    /**
     * The watch's requests for a reading, heard and not yet answered, and the
     * answers every message carries ([Answers]). Read and changed only through
     * [asks], under one lock for every Store: the listener adds to them while a
     * send settles them.
     */
    fun <T> asks(change: (pending: MutableList<Answers.Pending>, answers: MutableList<Answers.Answer>) -> T): T =
        synchronized(ASKS) {
            val pending = lines("askPending").mapNotNull { (id, at) ->
                val i = id.toIntOrNull()
                val t = at.toLongOrNull()
                if (i != null && t != null) Answers.Pending(i, t) else null
            }.toMutableList()
            val answers = lines("askAnswers").mapNotNull { (id, word) ->
                id.toIntOrNull()?.let { Answers.Answer(it, word) }
            }.toMutableList()
            val result = change(pending, answers)
            prefs.edit()
                .putString("askPending", pending.takeLast(Answers.KEEP).joinToString("\n") { "${it.id}\t${it.heardAt}" })
                .putString("askAnswers", answers.takeLast(Answers.KEEP).joinToString("\n") { "${it.id}\t${it.word.replace('\t', ' ').replace('\n', ' ')}" })
                .apply()
            result
        }

    /** Answer the watch's ask [id] with [word] ("" for done). */
    fun answer(id: Int, word: String) = asks { _, answers ->
        val kept = Answers.keep(answers, listOf(Answers.Answer(id, word)))
        answers.clear()
        answers.addAll(kept)
    }

    /**
     * Answer the watch's ask [id] with [word] for an ask not done, unless a
     * reading answered it meanwhile (a job begun just after it was heard).
     * True if this is its answer.
     */
    fun refuse(id: Int, word: String): Boolean = asks { pending, answers ->
        if (answers.any { it.id == id }) return@asks false
        pending.removeAll { it.id == id }
        val kept = Answers.keep(answers, listOf(Answers.Answer(id, word)))
        answers.clear()
        answers.addAll(kept)
        true
    }

    private fun lines(key: String): List<Pair<String, String>> =
        prefs.getString(key, null)?.lines()?.mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.orEmpty()

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
        /** Held while the watch's asks are read and changed ([asks]), across every Store. */
        private val ASKS = Any()
        private const val JOURNAL_LINES = 300
        private val STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}
