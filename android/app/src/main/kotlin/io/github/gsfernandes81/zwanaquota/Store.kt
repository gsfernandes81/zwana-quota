package io.github.gsfernandes81.zwanaquota

import android.content.Context
import io.github.gsfernandes81.zwanaquota.core.Answers
import io.github.gsfernandes81.zwanaquota.core.Reading
import io.github.gsfernandes81.zwanaquota.core.Session
import io.github.gsfernandes81.zwanaquota.core.TileWidth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
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

    fun save(reading: Reading) = replace(cache, reading.toJson().toString())

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

    /**
     * Written whole and renamed into place, so a reader never sees half of
     * one; one writer at a time across every Store, since workers, the
     * listener's threads and the screen each make their own.
     */
    private fun replace(file: File, text: String) = synchronized(FILES) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    /**
     * Whether the watch is in use: whenever Garmin Connect is installed
     * ([Garmin.present]). It is sent readings and listened to, with no
     * setting; whether Garmin Connect is signed in and a watch connected is
     * found out by trying, and a send that cannot go says why.
     */
    val watchOn: Boolean
        get() = Garmin.present(app)

    /**
     * Whether the watch app has ever been heard from: its hello on opening,
     * or any ask. Until it has, the app's screen says to open it on the
     * watch, since a watch app never opened receives nothing.
     */
    var watchHeard: Boolean
        get() = prefs.getBoolean("watchHeard", false)
        set(value) = prefs.edit().putBoolean("watchHeard", value).apply()

    /**
     * Whether the watch may also switch data and take devices off: the one
     * watch setting, off until switched on.
     */
    var watchCanControl: Boolean
        get() = prefs.getBoolean("watchCanControl", false)
        set(value) = prefs.edit().putBoolean("watchCanControl", value).apply()

    /**
     * How wide the Quick Settings tile was dragged: Android never says, so
     * it is asked (TileWidth). Standard unless set.
     */
    var tileWidth: TileWidth
        get() = TileWidth.entries.firstOrNull { it.name == prefs.getString("tileWidth", null) } ?: TileWidth.STANDARD
        set(value) = prefs.edit().putString("tileWidth", value.name).apply()

    /** The MAC of each device the watch was last offered to take off, by IP. */
    var offeredMacs: Map<String, String>
        get() = lines("offeredMacs").toMap()
        set(value) = prefs.edit().putString("offeredMacs", value.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()

    /**
     * Whether a message from this phone has reached the watch app, and no
     * send has found the watch unpaired since: what makes the screen-off send
     * worth a portal read (QuotaWorker), so a Garmin Connect installed for
     * some other device costs nothing at night.
     */
    var watchReached: Boolean
        get() = prefs.getBoolean("watchReached", false)
        set(value) = prefs.edit().putBoolean("watchReached", value).apply()

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

    /**
     * Add a line to the journal, read and rewritten as one step (the lock is
     * re-entrant, so [replace] takes it again) and renamed into place, so the
     * screen never reads half of it. Never throws: it is called from bare
     * threads and catch blocks, and a line lost to a full disk is the right
     * failure, where a crash would take the listener down with it.
     */
    fun log(text: String) = synchronized(FILES) {
        try {
            val lines = (if (journal.exists()) journal.readLines() else emptyList()) + "${stamp()} $text"
            replace(journal, lines.takeLast(JOURNAL_LINES).joinToString("\n", postfix = "\n"))
        } catch (_: IOException) {
        }
    }

    fun journal(): String = if (journal.exists()) journal.readText() else ""

    private fun stamp(): String = LocalDateTime.now().format(STAMP)

    companion object {
        /** Held while the watch's asks are read and changed ([asks]), across every Store. */
        private val ASKS = Any()

        /** Held while a file is written ([replace], [log]), across every Store. */
        private val FILES = Any()
        private const val JOURNAL_LINES = 300
        private val STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}
