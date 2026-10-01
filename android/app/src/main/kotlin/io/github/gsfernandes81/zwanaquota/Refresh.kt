package io.github.gsfernandes81.zwanaquota

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.text.format.DateFormat
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.gsfernandes81.zwanaquota.core.Answers
import io.github.gsfernandes81.zwanaquota.core.Datagram
import io.github.gsfernandes81.zwanaquota.core.Face
import io.github.gsfernandes81.zwanaquota.core.FallbackTransport
import io.github.gsfernandes81.zwanaquota.core.Names
import io.github.gsfernandes81.zwanaquota.core.Pipeline
import io.github.gsfernandes81.zwanaquota.core.PortalClient
import io.github.gsfernandes81.zwanaquota.core.PortalError
import io.github.gsfernandes81.zwanaquota.core.Reading
import io.github.gsfernandes81.zwanaquota.core.Route
import io.github.gsfernandes81.zwanaquota.core.Session
import io.github.gsfernandes81.zwanaquota.core.SessionAction
import io.github.gsfernandes81.zwanaquota.core.SessionChanged
import io.github.gsfernandes81.zwanaquota.core.TooLate
import io.github.gsfernandes81.zwanaquota.core.session
import io.github.gsfernandes81.zwanaquota.core.UrlConnectionTransport
import io.github.gsfernandes81.zwanaquota.core.WatchCommand
import io.github.gsfernandes81.zwanaquota.core.WatchPayload
import io.github.gsfernandes81.zwanaquota.core.WatchSession
import io.github.gsfernandes81.zwanaquota.core.remove
import io.github.gsfernandes81.zwanaquota.core.removable
import io.github.gsfernandes81.zwanaquota.core.apply
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * One refresh: read the portal if the cache is too old, draw the widget, and,
 * when a send is wanted, send the watch the newest reading.
 *
 * quota_widget.current()'s policy, minus its lock and its detached child --
 * WorkManager's unique work is both of those. The cache is answered from for
 * [MAX_AGE_SECONDS]; anything older is read again, and a read that fails
 * leaves the old reading standing and drawn *as* old, never as current.
 */
class Refresher(context: Context) {
    private val app = context.applicationContext
    private val store = Store(app)
    private val vault = Vault(app)

    /**
     * [notice], when given, is drawn in place of the footnote: what a data
     * switch that did not happen has to say, on the face it did not change.
     */
    fun run(force: Boolean, pushWanted: Boolean, trigger: String, notice: String? = null) {
        val now = Instant.now()
        var reading = store.reading()
        var live = false
        var why: String? = null
        // What a watch that asked is told when there is no new reading for it.
        var whyWatch: String? = null
        val path = Networks.path(app)

        val age = reading?.let { Pipeline.epochSeconds(now) - it.ts }
        if (force || age == null || age > MAX_AGE_SECONDS) {
            if (!vault.signedIn) {
                why = "sign in: open the app"
                whyWatch = "sign in on phone"
                store.note("read", "not signed in")
            } else {
                val (client, network) = connect(path)
                try {
                    reading = client.read(reading?.carry(), now).also(store::save)
                    live = true
                    store.note("read", "ok over ${network()} ($trigger)")
                    readSession(client)
                } catch (e: PortalError) {
                    why = "portal: ${e.message}"
                    whyWatch = "portal not reached"
                    store.note("read", "failed over ${network()}: ${e.message}")
                }
            }
        }

        var face = faceOf(reading, live, now, why)
        if (notice != null) face = face.copy(footnote = notice, warning = true)
        QuotaWidget.draw(app, face)
        // Names come after the first drawing: a device the network is slow
        // to name is drawn by its IP meanwhile, never holds up the figure.
        if (live && nameDevices(path)) QuotaWidget.draw(app, face)

        if (pushWanted) push(reading, now.toEpochMilli(), whyWatch)
    }

    /**
     * Throw the data switch, then read everything again so the face shows
     * what it did. The action is checked against the session as it is now
     * ([PortalClient.apply]), so a tap on a picture drawn before someone
     * else changed things does nothing rather than the wrong thing.
     */
    fun switch(action: SessionAction, pushWanted: Boolean, trigger: String, askId: Int?, deadline: Long) =
        change(action.name.lowercase(), "switch failed", pushWanted, trigger, askId, deadline) { client, allowed ->
            client.apply(action, allowed)
        }

    /**
     * Take one other device off the session, then read everything again so
     * the widget and the watch show it. [remove] checks the device is still
     * one this phone may take off.
     */
    fun removeDevice(ip: String, pushWanted: Boolean, trigger: String, askId: Int?, deadline: Long) =
        change("remove $ip", "remove failed", pushWanted, trigger, askId, deadline) { client, allowed ->
            client.remove(ip, expectedMac = store.offeredMacs[ip], allowed = allowed)
        }

    /**
     * Make one change to the session with [send], given whether it may still
     * be sent ([deadline]), then read everything again. What came of it is
     * noted as [what], drawn on the face if it was not done, and answers the
     * watch's ask [askId], if there is one -- [failed] for either when the
     * portal refused it.
     */
    private fun change(
        what: String,
        failed: String,
        pushWanted: Boolean,
        trigger: String,
        askId: Int?,
        deadline: Long,
        send: (PortalClient, () -> Boolean) -> Session,
    ) {
        if (!vault.signedIn) {
            askId?.let { store.answer(it, "sign in on phone") }
            return run(force = false, pushWanted, trigger)
        }
        val (client, network) = connect(Networks.path(app))
        val (notice, word) = try {
            store.save(send(client) { Instant.now().epochSecond <= deadline })
            store.note("session", "$what ok over ${network()} ($trigger)")
            null to ""
        } catch (e: TooLate) {
            store.note("session", "$what not sent: ${e.message}")
            "too late: nothing done" to "too late"
        } catch (e: SessionChanged) {
            store.note("session", "$what not sent: ${e.message}")
            readSession(client)
            "changed elsewhere: nothing done" to "changed elsewhere"
        } catch (e: PortalError) {
            store.note("session", "$what failed over ${network()}: ${e.message}")
            "$failed: ${e.message}" to failed
        }
        askId?.let { store.answer(it, word) }
        run(force = true, pushWanted, trigger, notice)
    }

    /** A client over the route [Networks.path] chose, and which route it ended up on. */
    private fun connect(path: Networks.Path): Pair<PortalClient, () -> String> {
        var network = path.label
        val direct = UrlConnectionTransport()
        val transport = path.pinned?.let { pinned ->
            // Pinning can still be refused (a VPN Android did not report
            // as the default); then the default route, which never
            // reached the portal the first time, so nothing is sent twice.
            FallbackTransport(UrlConnectionTransport(pinned), direct) {
                network = "the default route (the phone refused Wi-Fi pinning: ${it.message})"
            }
        } ?: direct
        val client = PortalClient(
            transport,
            vault.cookies(),
            credentials = { vault.credentials() },
            saveSession = { vault.saveCookies(it) },
        )
        return client to { network }
    }

    /** The data session, beside the reading. Its failure is noted, never the reading's. */
    private fun readSession(client: PortalClient) {
        try {
            val session = client.session()
            if (session != store.session()) store.note("session", "data ${if (session.on) "on" else "off"}, ${session.role.name.lowercase()}")
            store.save(session)
        } catch (e: PortalError) {
            store.note("session", "not read: ${e.message}")
        }
    }

    /**
     * Ask the network for the name of each device in the session not named
     * lately. True if any name changed. A name is asked again after
     * [NAMED_FOR_SECONDS] (addresses get handed out again), and a device
     * nothing answered for after [UNNAMED_FOR_SECONDS].
     */
    private fun nameDevices(path: Networks.Path): Boolean {
        val session = store.session()?.takeIf { it.on } ?: return false
        val now = Instant.now().epochSecond
        val known = store.names().toMutableMap()
        val due = session.devices.filter { ip ->
            ip != session.myIp && known[ip].let { n ->
                n == null || now - n.at > if (n.name.isEmpty()) UNNAMED_FOR_SECONDS else NAMED_FOR_SECONDS
            }
        }.take(MAX_NAMED_PER_READ)
        if (due.isEmpty()) return false
        val net = Networks.datagram(path)
        val dns = Networks.dnsServers(app, path)
        var changed = false
        for (ip in due) {
            val name = Names.resolve(ip, dns, net).orEmpty()
            if (known[ip]?.name.orEmpty() != name) changed = true
            known[ip] = Store.Named(name, now)
        }
        store.saveNames(known.entries.sortedByDescending { it.value.at }.take(64).associate { it.key to it.value })
        if (changed) store.note("names", due.joinToString { "$it=${known[it]?.name?.ifEmpty { "?" }}" })
        return changed
    }

    /**
     * Send the watch the newest reading there is. Messages are made and handed
     * to Garmin Connect one at a time, whichever job they come from, and each
     * is made inside its turn: the newer of this run's reading and the one
     * stored by then (by `ts`, when each read began; another run may have read
     * since), with the session as stored. (The `reset` sent is the one after
     * the reading, whenever the message is made: WatchPayload.) `sent` is the
     * moment the message is made, in whole seconds, so while the clock runs
     * forward a later message never carries an earlier stamp -- and that
     * stamp, not the order they reach the watch (a send the phone gave up
     * waiting on may still arrive), is what the watch goes by: it keeps a
     * message only if it is not older than the one it has. So the message the
     * watch keeps is the newest it was given, and offeredMacs holds the
     * devices of the newest made -- the same, unless that send did not get
     * through.
     * A newer reading another run stored is sent instead of [reading], and
     * is sent even when this run has none.
     * With no reading anywhere there is nothing to send. Each message also
     * answers the watch's asks ([Answers]): the requests for a reading heard
     * before its reading began, or, with [why] (why this run read nothing
     * new; null when it did), before [tried] (when this run began, epoch ms),
     * and every switch or removal a job has answered.
     */
    private fun push(reading: Reading?, tried: Long? = null, why: String? = null) {
        synchronized(SENDING) {
            val stored = store.reading()
            val newest = when {
                reading == null -> stored ?: return
                stored != null && stored.ts > reading.ts -> stored
                else -> reading
            }
            val now = Instant.now()
            // `live` only shapes the phone face's age mark; the watch judges age from `ts`.
            val doc = Pipeline.derive(newest, Pipeline.epochSeconds(now) - newest.ts, false, now)
            val face = Face.of(doc, ZoneId.systemDefault(), hour24(app))
            // Offered only while the listener is actually up, not merely
            // switched on: Android can refuse to restart it, and a watch
            // should not offer what nobody will hear.
            val canAsk = store.watchEnabled && store.watchCanAsk && WatchListener.running
            val names = store.names().filterValues { it.name.isNotEmpty() }.mapValues { it.value.name }
            val known = store.session()?.takeIf { vault.signedIn }
            val canControl = canAsk && store.watchCanControl
            val session = known?.let { WatchSession.fields(it, names, canControl) }.orEmpty()
            // Which device each offered address was, so a removal asked for
            // later cannot take off whoever has the address by then.
            store.offeredMacs = if (known != null && canControl) known.macs.filterKeys { known.removable(it) } else emptyMap()
            val answers = store.asks { pending, answers ->
                val (still, settled) = Answers.settle(pending, newest.ts, tried, why, now.toEpochMilli())
                pending.clear()
                pending.addAll(still)
                val kept = Answers.keep(answers, settled)
                answers.clear()
                answers.addAll(kept)
                kept
            }
            val payload = WatchPayload.build(doc, face, now, EVERY_SECONDS, canAsk, session, answers)
            // A message that only answers an ask is not the periodic send's
            // reading (QuotaWorker's screen-off check).
            if (reading != null) store.lastPush = now.epochSecond
            store.note("watch", Garmin.send(app, payload).joinToString("; "))
        }
    }

    /** Send the watch the stored reading and the answers, reading nothing: how a refused ask is told. */
    fun sendStored() = push(null)

    private fun faceOf(reading: Reading?, live: Boolean, now: Instant, why: String?): Face {
        val zone = ZoneId.systemDefault()
        if (reading == null) return Face.unknown(now, zone, why ?: "tap to read", hour24(app))
        val doc = Pipeline.derive(reading, Pipeline.epochSeconds(now) - reading.ts, live, now)
        return Face.of(doc, zone, hour24(app))
    }

    companion object {
        /** Held for the whole of a send to the watch ([push]). */
        private val SENDING = Any()

        /** quota_widget.DEFAULT_MAX_AGE and the tile's CACHE_MAX_AGE. */
        const val MAX_AGE_SECONDS = 45.0

        /**
         * How often the watch is sent a reading: the Tasker tile's "kept
         * fresh" profile runs every 30 minutes (docs/quota-tile.md), and this
         * is the same cadence rather than a new one. The watch draws a reading
         * older than twice this as stale.
         */
        const val EVERY_MINUTES = 30L
        const val EVERY_SECONDS = (EVERY_MINUTES * 60).toInt()

        /**
         * How often the widget is kept fresh while the screen is on: the
         * Tasker tile's cadence, and WorkManager's shortest period. The
         * system's own widget update (updatePeriodMillis) cannot go below 30.
         */
        const val KEEP_FRESH_MINUTES = 15L

        const val NAMED_FOR_SECONDS = 6 * 3600L
        const val UNNAMED_FOR_SECONDS = 3600L
        const val MAX_NAMED_PER_READ = 6

        /** The phone's own clock setting, which every time drawn follows. */
        fun hour24(context: Context): Boolean = DateFormat.is24HourFormat(context)

        /** Cached reading, derived as of now: what the widget can draw instantly. */
        fun cachedFace(context: Context): Face {
            val now = Instant.now()
            val reading = Store(context).reading()
                ?: return Face.unknown(
                    now,
                    ZoneId.systemDefault(),
                    if (Vault(context).signedIn) "tap to read" else "sign in: open the app",
                    hour24(context),
                )
            val doc = Pipeline.derive(reading, Pipeline.epochSeconds(now) - reading.ts, false, now)
            return Face.of(doc, ZoneId.systemDefault(), hour24(context))
        }
    }
}

/**
 * Which way the portal is reached: [Route.choose] decides, from what the
 * phone says about its networks. Pinned to the Wi-Fi only around a default
 * route that is neither the Wi-Fi nor a VPN; a VPN firewall (GlassWire and
 * the like) is gone through, since Android refuses a socket bound around it.
 */
object Networks {
    /** [wifi] is the Wi-Fi the portal lives on, if the phone has one; it is only bound to when [route] says so. */
    class Path(val route: Route, val label: String, val wifi: Network?) {
        val pinned: ((URL) -> URLConnection)?
            get() = if (route == Route.PIN_WIFI && wifi != null) { url: URL -> wifi.openConnection(url) } else null
    }

    fun path(context: Context): Path {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return Path(Route.DEFAULT, "the default route", null)
        val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val vpn = active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val wifiDefault = !vpn && active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return when (Route.choose(vpn, wifiDefault, wifi != null)) {
            Route.PIN_WIFI -> Path(Route.PIN_WIFI, "Wi-Fi (pinned: the default route is not Wi-Fi)", wifi)
            Route.DEFAULT -> Path(
                Route.DEFAULT,
                when {
                    vpn -> "a VPN (a firewall like GlassWire?)"
                    wifiDefault -> "Wi-Fi"
                    else -> "mobile data (no Wi-Fi)"
                },
                wifi,
            )
        }
    }

    /** The Wi-Fi's own DNS servers: the router that handed out the addresses, usually. */
    fun dnsServers(context: Context, path: Path): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val wifi = path.wifi ?: return emptyList()
        return cm.getLinkProperties(wifi)?.dnsServers.orEmpty()
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }

    /**
     * UDP over the same route as the portal: bound to the Wi-Fi only where
     * the portal's requests are, and unbound if the phone refuses.
     */
    fun datagram(path: Path): Datagram = Datagram { host, port, query, timeoutMillis ->
        DatagramSocket().use { socket ->
            if (path.route == Route.PIN_WIFI) {
                try {
                    path.wifi?.bindSocket(socket)
                } catch (_: IOException) {
                    // EPERM under a VPN: the default route it is.
                }
            }
            // A literal address, so no lookup happens here.
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(query, query.size, address, port))
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            val buffer = ByteArray(1500)
            var answer: ByteArray? = null
            while (answer == null) {
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left <= 0) break
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                // Anything else arriving on the port is not the answer.
                if (packet.address == address) answer = buffer.copyOf(packet.length)
            }
            answer
        }
    }
}

/** The one Worker: every refresh, tapped, periodic or pressed, runs through it. */
class QuotaWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = Store(applicationContext)
        val trigger = inputData.getString(TRIGGER) ?: "?"
        try {
            val refresher = Refresher(applicationContext)
            val action = inputData.getString(ACTION)?.let { name -> SessionAction.entries.firstOrNull { it.name == name } }
            val remove = inputData.getString(REMOVE)
            val askId = inputData.getInt(ASK_ID, -1).takeIf { it >= 0 }
            // A switch runs by its deadline or not at all: one the system held
            // back is one the person has given up on, and may since have done
            // another way -- and from the watch, one it has already said went
            // unanswered (Work.fromWatch). Checked again just before the change
            // is sent (Refresher.change hands PortalClient.apply/remove the
            // deadline as `allowed`).
            val deadline = inputData.getLong(DEADLINE, 0)
            if ((action != null || remove != null) && Instant.now().epochSecond > deadline) {
                store.note("session", "${action?.name?.lowercase() ?: "remove $remove"} past its deadline; too late, nothing done")
                QuotaWidget.draw(applicationContext, Refresher.cachedFace(applicationContext).copy(footnote = "too late: nothing done", warning = true))
                if (askId != null) {
                    store.answer(askId, "too late")
                    refresher.sendStored()
                }
                return Result.success()
            }
            when {
                inputData.getBoolean(ANSWER, false) -> refresher.sendStored()
                action != null -> refresher.switch(action, store.watchEnabled, trigger, askId, deadline)
                remove != null -> refresher.removeDevice(remove, store.watchEnabled, trigger, askId, deadline)
                inputData.getBoolean(PERIODIC, false) -> {
                    // Screen on: read, as the Tasker tile's profile does, and
                    // send the watch a reading too. Screen off: nobody is
                    // looking at the widget, so nothing -- unless the watch has
                    // gone its send interval without one.
                    val screenOn = applicationContext.getSystemService(PowerManager::class.java)?.isInteractive != false
                    val watchDue = store.watchEnabled &&
                        Instant.now().epochSecond - store.lastPush >= Refresher.EVERY_SECONDS - 5 * 60
                    if (!screenOn && !watchDue) return Result.success()
                    refresher.run(force = false, pushWanted = store.watchEnabled, trigger = trigger)
                }
                else -> refresher.run(
                    force = inputData.getBoolean(FORCE, false),
                    pushWanted = inputData.getBoolean(PUSH, false) || store.watchEnabled,
                    trigger = trigger,
                )
            }
            store.note("worker", "ran ($trigger)")
            // The listener, if it should be running and the system stopped it.
            WatchListener.sync(applicationContext, "worker")
        } catch (e: Exception) {
            // A failure here is drawn and noted, never retried in a loop: the
            // next tap or the next period is the retry.
            store.note("worker", "failed ($trigger): ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            // Never leave "reading the portal..." standing.
            try {
                QuotaWidget.draw(applicationContext, Refresher.cachedFace(applicationContext))
            } catch (_: Exception) {
            }
        }
        return Result.success()
    }

    companion object {
        const val FORCE = "force"
        const val PUSH = "push"
        const val TRIGGER = "trigger"
        const val ACTION = "action"
        const val PERIODIC = "periodic"
        const val REMOVE = "remove"
        const val DEADLINE = "deadline"
        const val ASK_ID = "askId"
        const val ANSWER = "answer"

        /** How long after the widget's tap its switch may still run. */
        const val SWITCH_LIFETIME_SECONDS = 180L

        /**
         * How long after the phone hears it a switch or removal from the watch
         * may still be sent to the portal. The watch stops waiting for the
         * answer Ask.WAIT_SWITCH (90 s) after sending, so a switch is made, if
         * at all, while the watch still waits -- unless the message took more
         * than the other 45 s to reach the phone. Those 45 s are also what the
         * answer has, after the switch, for the read that follows it, any
         * device names due, another send already in hand, and the send back
         * (Garmin Connect up to 10 s to be ready, then the watch): a
         * portal slow enough to use them up leaves the switch made and the
         * watch saying it went unanswered, until the message shows the session.
         * tests/test_watch_contract.py holds the two numbers to each other.
         */
        const val WATCH_SWITCH_SECONDS = 45L
    }
}

/** What is enqueued, and under which name, so two taps are one read. */
object Work {
    private const val REFRESH = "refresh"
    private const val PUSH = "push-now"
    private const val KEEP_FRESH = "keep-fresh"

    /** The watch-only periodic send before the widget had one of its own. */
    private const val OLD_WATCH = "watch-every-30m"
    private const val SWITCH = "data-switch"
    private const val ASKED = "watch-asked"
    private const val ANSWER = "watch-answer"

    fun refresh(context: Context, force: Boolean, trigger: String) = enqueue(context, REFRESH, force, false, trigger)

    /**
     * Throw the data switch. KEEP, so a second tap while the first is still
     * on its way is dropped rather than sent after it.
     */
    fun switch(context: Context, action: SessionAction) {
        val input = Data.Builder().putAll(input(true, false, "widget switch")).putString(QuotaWorker.ACTION, action.name)
            .putLong(QuotaWorker.DEADLINE, Instant.now().epochSecond + QuotaWorker.SWITCH_LIFETIME_SECONDS).build()
        val request = OneTimeWorkRequestBuilder<QuotaWorker>().setInputData(input).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SWITCH, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * The watch asked to switch data or take a device off, as ask [askId]
     * (null from a watch build older than ids). The listener has already
     * refused it while another switch is on its way ([switchBusy]); KEEP
     * covers the moment between, and the worker checks it against the portal
     * first, and against [QuotaWorker.WATCH_SWITCH_SECONDS] from now.
     */
    fun fromWatch(context: Context, command: WatchCommand, askId: Int?) {
        val data = Data.Builder().putAll(input(true, true, "watch"))
            .putLong(QuotaWorker.DEADLINE, Instant.now().epochSecond + QuotaWorker.WATCH_SWITCH_SECONDS)
        askId?.let { data.putInt(QuotaWorker.ASK_ID, it) }
        when (command) {
            is WatchCommand.Act -> data.putString(QuotaWorker.ACTION, command.action.name)
            is WatchCommand.Remove -> data.putString(QuotaWorker.REMOVE, command.ip)
            WatchCommand.Refresh -> return askedByWatch(context)
        }
        val request = OneTimeWorkRequestBuilder<QuotaWorker>().setInputData(data.build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SWITCH, ExistingWorkPolicy.KEEP, request)
    }

    /** A switch or removal, the widget's or the watch's, still on its way. Blocks: not on the main thread. */
    fun switchBusy(context: Context): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SWITCH).get().any { !it.state.isFinished }

    /**
     * Send the watch the answers as they stand ([Store.answer]), reading
     * nothing: how an ask the phone refused is told. Appended after one
     * already running, which may have read the answers before this one.
     */
    fun answer(context: Context) {
        val input = Data.Builder().putAll(input(false, true, "watch answer")).putBoolean(QuotaWorker.ANSWER, true).build()
        val request = OneTimeWorkRequestBuilder<QuotaWorker>().setInputData(input).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ANSWER, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * The watch asked for a reading: read now and send it back (the newest
     * reading stored, if the read fails at the portal or is not made; nothing
     * if none is). An ask is answered only by a reading begun after the phone
     * heard it ([Answers.settle]), so KEEP would drop an ask heard after the
     * running job's read began, and APPEND alone would queue a read per ask.
     * So: one job queued behind the running one at most. With one already
     * queued, nothing is added -- WorkManager marks a job running before it
     * starts, so one seen queued here begins its read after this ask was
     * heard and answers it. Otherwise one job, after any running. However
     * often the watch asks and however long the portal takes, that is one
     * read running and one waiting. Blocks: not on the main thread.
     */
    fun askedByWatch(context: Context) = synchronized(ASKING) {
        val queued = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ASKED).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (!queued) enqueue(context, ASKED, true, true, "watch asked", ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    /** Held while [askedByWatch] looks at the queue and adds to it: asks are heard on threads of their own. */
    private val ASKING = Any()

    /** Send to the watch now, whether or not the periodic send is on: the test button. */
    fun pushNow(context: Context) = enqueue(context, PUSH, false, true, "button", ExistingWorkPolicy.REPLACE)

    /**
     * The one periodic job, every [Refresher.KEEP_FRESH_MINUTES] (WorkManager's
     * shortest), while there is anything to keep fresh: a widget on the home
     * screen or the watch switched on. What each run does is the worker's
     * call ([QuotaWorker]): a read while the screen is on, as the Tasker tile
     * does, and with it off nothing, unless the watch is due a send.
     */
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(OLD_WATCH)
        if (Store(context).watchEnabled || QuotaWidget.placed(context)) {
            val request = PeriodicWorkRequestBuilder<QuotaWorker>(
                Refresher.KEEP_FRESH_MINUTES, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES,
            ).setInputData(
                Data.Builder()
                    .putAll(input(false, false, "every ${Refresher.KEEP_FRESH_MINUTES}m"))
                    .putBoolean(QuotaWorker.PERIODIC, true)
                    .build(),
            ).build()
            manager.enqueueUniquePeriodicWork(KEEP_FRESH, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            manager.cancelUniqueWork(KEEP_FRESH)
        }
    }

    private fun enqueue(
        context: Context,
        name: String,
        force: Boolean,
        push: Boolean,
        trigger: String,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val request = OneTimeWorkRequestBuilder<QuotaWorker>().setInputData(input(force, push, trigger)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }

    // No network constraint, on purpose: a captive Wi-Fi with the quota spent
    // is exactly when Android calls the network unusable, and exactly when the
    // reading matters. A read that cannot connect fails and is drawn as such.
    private fun input(force: Boolean, push: Boolean, trigger: String): Data = Data.Builder()
        .putBoolean(QuotaWorker.FORCE, force)
        .putBoolean(QuotaWorker.PUSH, push)
        .putString(QuotaWorker.TRIGGER, trigger)
        .build()
}
