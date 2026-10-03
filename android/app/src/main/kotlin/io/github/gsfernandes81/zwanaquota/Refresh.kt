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
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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
     * [asked]: this is the read the watch asked for (Work.fromWatch).
     */
    fun run(force: Boolean, pushWanted: Boolean, trigger: String, notice: String? = null, asked: Boolean = false) {
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
                    whyWatch = "portal read failed"
                    store.note("read", "failed over ${network()}: ${e.message}")
                }
            }
        }

        var face = faceOf(reading, live, now, why)
        if (notice != null) face = face.copy(footnote = notice, warning = true)
        Faces.draw(app, face)
        // Names come after the first drawing: a device the network is slow
        // to name is drawn by its IP meanwhile, never holds up the figure.
        if (live && nameDevices(path)) Faces.draw(app, face)

        // Only the watch's own read answers its request with why it has no
        // reading: another job's failure says nothing of the read it waits on.
        if (pushWanted) push(reading, now.toEpochMilli(), whyWatch.takeIf { asked })
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
     * the widget, the tile and the watch show it. [remove] checks the device
     * is still one this phone may take off, and still the one with
     * [expectedMac] -- the MAC it had where it was offered -- when known.
     */
    fun removeDevice(ip: String, expectedMac: String?, pushWanted: Boolean, trigger: String, askId: Int?, deadline: Long) =
        change("remove $ip", "remove failed", pushWanted, trigger, askId, deadline) { client, allowed ->
            client.remove(ip, expectedMac = expectedMac, allowed = allowed)
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
     * before its reading began, or, with [why] (why the read the watch asked
     * for has nothing new; null when it has, and for any read it did not ask
     * for), before [tried] (when this run began, epoch ms), and every switch
     * or removal a job has answered.
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
            // wanted: Android can refuse to restart it, and a watch should
            // not offer what nobody will hear.
            val canAsk = WatchListener.running
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
            val sent = Garmin.send(app, payload)
            store.note("watch", sent.lines.joinToString("; "))
            // A watch reached makes the screen-off send worth its read; one
            // no longer paired ends it.
            if (sent.delivered && !store.watchReached) store.watchReached = true
            if (sent.unpaired && store.watchReached) store.watchReached = false
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
         * How often the watch is sent a reading: the retired Tasker tile's
         * "kept fresh" profile ran every 30 minutes, and this kept that
         * cadence rather than inventing a new one. The watch draws a reading
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
            // another way -- and from the watch, one it is told was too late
            // while it still waits (Work.fromWatch). Checked again just before
            // the change is sent (Refresher.change hands PortalClient.apply/
            // remove the deadline as `allowed`).
            val deadline = inputData.getLong(DEADLINE, 0)
            if ((action != null || remove != null) && Instant.now().epochSecond > deadline) {
                store.note("session", "${action?.name?.lowercase() ?: "remove $remove"} past its deadline; too late, nothing done")
                Faces.draw(applicationContext, Refresher.cachedFace(applicationContext).copy(footnote = "too late: nothing done", warning = true))
                if (askId != null) {
                    store.answer(askId, "too late")
                    if (store.watchOn) refresher.sendStored()
                }
                return Result.success()
            }
            when {
                action != null -> refresher.switch(action, store.watchOn, trigger, askId, deadline)
                remove != null -> refresher.removeDevice(remove, inputData.getString(MAC), store.watchOn, trigger, askId, deadline)
                inputData.getBoolean(PERIODIC, false) -> {
                    // Screen on: read, as the Tasker tile's profile does, and
                    // send the watch a reading too. Screen off: nobody is
                    // looking at the widget, so nothing -- unless the watch has
                    // gone its send interval without one.
                    val screenOn = applicationContext.getSystemService(PowerManager::class.java)?.isInteractive != false
                    // Only for a watch this phone has reached: Garmin Connect
                    // installed for some other device is no reason to read
                    // the portal all night.
                    val watchDue = store.watchOn && store.watchReached &&
                        Instant.now().epochSecond - store.lastPush >= Refresher.EVERY_SECONDS - 5 * 60
                    if (!screenOn && !watchDue) return Result.success()
                    refresher.run(force = false, pushWanted = store.watchOn, trigger = trigger)
                }
                else -> refresher.run(
                    force = inputData.getBoolean(FORCE, false),
                    pushWanted = inputData.getBoolean(PUSH, false) || store.watchOn,
                    trigger = trigger,
                    asked = inputData.getBoolean(ASKED, false),
                )
            }
            store.note("worker", "ran ($trigger)")
        } catch (e: Exception) {
            // A failure here is drawn and noted, never retried in a loop: the
            // next tap or the next period is the retry.
            store.note("worker", "failed ($trigger): ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            // Never leave "reading the portal..." standing.
            try {
                Faces.draw(applicationContext, Refresher.cachedFace(applicationContext))
            } catch (_: Exception) {
            }
        } finally {
            // Whichever way the run went, early returns included: the
            // listener, if it should be running and the system stopped it;
            // and the periodic job, if what it keeps fresh came or went (a
            // widget, or Garmin Connect installed or removed).
            try {
                WatchListener.sync(applicationContext, "worker")
            } catch (e: Exception) {
                store.note("listener", "not synced: ${e.javaClass.simpleName}")
            }
            try {
                Work.schedule(applicationContext)
            } catch (e: Exception) {
                store.note("worker", "not rescheduled: ${e.javaClass.simpleName}")
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
        const val MAC = "mac"
        const val DEADLINE = "deadline"
        const val ASK_ID = "askId"
        const val ASKED = "asked"

        /** How long after the widget's or the tile's tap its switch or removal may still run. */
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

    fun refresh(context: Context, force: Boolean, trigger: String) = enqueue(context, REFRESH, force, false, trigger)

    /**
     * Throw the data switch. KEEP, so a second tap while the first is still
     * on its way is dropped rather than sent after it.
     */
    fun switch(context: Context, action: SessionAction, trigger: String = "widget switch") =
        change(context, Data.Builder().putAll(input(true, false, trigger)).putString(QuotaWorker.ACTION, action.name))

    /**
     * Take the device at [ip] off data, from the tile's panel: only if it is
     * still the device with [mac] there, when the portal listed one. The same
     * one-at-a-time as [switch], under the same name, so a removal and a
     * switch never cross.
     */
    fun remove(context: Context, ip: String, mac: String?) =
        change(
            context,
            Data.Builder().putAll(input(true, false, "tile")).putString(QuotaWorker.REMOVE, ip)
                .apply { mac?.let { putString(QuotaWorker.MAC, it) } },
        )

    private fun change(context: Context, input: Data.Builder) {
        input.putLong(QuotaWorker.DEADLINE, Instant.now().epochSecond + QuotaWorker.SWITCH_LIFETIME_SECONDS)
        val request = OneTimeWorkRequestBuilder<QuotaWorker>().setInputData(input.build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SWITCH, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * What the watch asked, as ask [askId] (null from a watch build older than
     * ids). True if it is on its way; false if one of its kind already is --
     * one request of each kind in flight, never a queue: a switch or removal
     * while another switch (the widget's or the watch's) is going, or a
     * request for a reading while another is being read. The caller tells the
     * watch "phone busy" ([Store.refuse]). Blocks: not on the main thread.
     *
     * A request for a reading is answered by the first reading its job begins
     * ([Answers.settle]), so it is noted as heard before the job is enqueued.
     * A switch or removal is checked against the portal first, and is made
     * only within [QuotaWorker.WATCH_SWITCH_SECONDS] of now.
     */
    fun fromWatch(context: Context, command: WatchCommand, askId: Int?): Boolean {
        val (key, value) = when (command) {
            WatchCommand.Refresh -> {
                askId?.let { id -> Store(context).asks { pending, _ -> pending.add(Answers.Pending(id, System.currentTimeMillis())) } }
                val reading = Data.Builder().putAll(input(true, true, "watch asked")).putBoolean(QuotaWorker.ASKED, true).build()
                return startIfIdle(context, ASKED, OneTimeWorkRequestBuilder<QuotaWorker>().setInputData(reading).build())
            }
            is WatchCommand.Act -> QuotaWorker.ACTION to command.action.name
            is WatchCommand.Remove -> QuotaWorker.REMOVE to command.ip
        }
        val switching = Data.Builder().putAll(input(true, true, "watch")).putString(key, value)
            .putLong(QuotaWorker.DEADLINE, Instant.now().epochSecond + QuotaWorker.WATCH_SWITCH_SECONDS)
        // Which device the watch was offered at that address, so a removal
        // cannot take off whoever has the address by the time it runs.
        if (command is WatchCommand.Remove) Store(context).offeredMacs[command.ip]?.let { switching.putString(QuotaWorker.MAC, it) }
        askId?.let { switching.putInt(QuotaWorker.ASK_ID, it) }
        return startIfIdle(context, SWITCH, OneTimeWorkRequestBuilder<QuotaWorker>().setInputData(switching.build()).build())
    }

    /**
     * Enqueue [request] as [name] unless one is already on its way, and say
     * which. KEEP drops it then, and whether it did is read back once the
     * enqueue has landed, so WorkManager itself decides: a request enqueued by
     * anyone in between (a widget tap) still counts, with no lock of our own.
     */
    private fun startIfIdle(context: Context, name: String, request: OneTimeWorkRequest): Boolean {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get()
        return manager.getWorkInfosForUniqueWork(name).get().any { it.id == request.id }
    }

    /** Send the watch a reading now: the watch setting changed, and the watch learns it from the next message. */
    fun pushNow(context: Context) = enqueue(context, PUSH, false, true, "setting", ExistingWorkPolicy.REPLACE)

    /**
     * The one periodic job, every [Refresher.KEEP_FRESH_MINUTES] (WorkManager's
     * shortest), while there is anything to keep fresh: a widget on the home
     * screen or the watch in use (Garmin Connect installed, Store.watchOn).
     * Each run asks again (QuotaWorker), so the job ends when neither is so.
     * What each run does is the worker's
     * call ([QuotaWorker]): a read while the screen is on, as the Tasker tile
     * does, and with it off nothing, unless the watch is due a send.
     */
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(OLD_WATCH)
        if (Store(context).watchOn || QuotaWidget.placed(context)) {
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
