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
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.gsfernandes81.zwanaquota.core.Datagram
import io.github.gsfernandes81.zwanaquota.core.Face
import io.github.gsfernandes81.zwanaquota.core.FallbackTransport
import io.github.gsfernandes81.zwanaquota.core.Names
import io.github.gsfernandes81.zwanaquota.core.Pipeline
import io.github.gsfernandes81.zwanaquota.core.PortalClient
import io.github.gsfernandes81.zwanaquota.core.PortalError
import io.github.gsfernandes81.zwanaquota.core.Reading
import io.github.gsfernandes81.zwanaquota.core.Route
import io.github.gsfernandes81.zwanaquota.core.SessionAction
import io.github.gsfernandes81.zwanaquota.core.SessionChanged
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
 * One refresh: read the portal if the cache is too old, draw the widget, and
 * send the watch the result if the watch is switched on.
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
        val path = Networks.path(app)

        val age = reading?.let { Pipeline.epochSeconds(now) - it.ts }
        if (force || age == null || age > MAX_AGE_SECONDS) {
            if (!vault.signedIn) {
                why = "sign in: open the app"
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

        if (pushWanted && reading != null) push(reading, live, now)
    }

    /**
     * Send the watch the reading there is, without reading the portal: the
     * answer to a watch that asked again within [WatchListener]'s gap, which
     * would otherwise wait out its minute and say "no answer from phone". A
     * watch takes a message as its answer only if its `sent` -- whole seconds
     * -- differs from the one it asked against, so this never sends in the
     * same second as the last send. Blocks: called off the main thread.
     */
    fun resend() {
        val reading = store.reading() ?: return
        val wait = (store.lastPush + 1) * 1000 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        push(reading, live = false, Instant.now())
    }

    /**
     * Throw the data switch, then read everything again so the face shows
     * what it did. The action is checked against the session as it is now
     * ([PortalClient.apply]), so a tap on a picture drawn before someone
     * else changed things does nothing rather than the wrong thing.
     */
    fun switch(action: SessionAction, pushWanted: Boolean, trigger: String) {
        if (!vault.signedIn) return run(force = false, pushWanted, trigger)
        val (client, network) = connect(Networks.path(app))
        val notice = try {
            store.save(client.apply(action))
            store.note("session", "${action.name.lowercase()} ok over ${network()} ($trigger)")
            null
        } catch (e: SessionChanged) {
            store.note("session", "${action.name.lowercase()} not sent: ${e.message}")
            readSession(client)
            "changed elsewhere: nothing done"
        } catch (e: PortalError) {
            store.note("session", "${action.name.lowercase()} failed over ${network()}: ${e.message}")
            "data switch failed: ${e.message}"
        }
        run(force = true, pushWanted, trigger, notice)
    }

    /**
     * Take one other device off the session, then read everything again so
     * the widget and the watch show it. [remove] checks the device is still
     * one this phone may take off.
     */
    fun removeDevice(ip: String, pushWanted: Boolean, trigger: String) {
        if (!vault.signedIn) return run(force = false, pushWanted, trigger)
        val (client, network) = connect(Networks.path(app))
        val notice = try {
            store.save(client.remove(ip, expectedMac = store.offeredMacs[ip]))
            store.note("session", "removed $ip over ${network()} ($trigger)")
            null
        } catch (e: SessionChanged) {
            store.note("session", "remove $ip not sent: ${e.message}")
            readSession(client)
            "changed elsewhere: nothing done"
        } catch (e: PortalError) {
            store.note("session", "remove $ip failed over ${network()}: ${e.message}")
            "remove failed: ${e.message}"
        }
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

    private fun push(reading: Reading, live: Boolean, now: Instant) {
        val doc = Pipeline.derive(reading, Pipeline.epochSeconds(now) - reading.ts, live, now)
        val face = Face.of(doc, ZoneId.systemDefault(), hour24(app))
        // Offered only while the listener is actually up, not merely switched
        // on: Android can refuse to restart it, and a watch should not offer
        // what nobody will hear.
        val canAsk = store.watchEnabled && store.watchCanAsk && WatchListener.running
        val names = store.names().filterValues { it.name.isNotEmpty() }.mapValues { it.value.name }
        val known = store.session()?.takeIf { vault.signedIn }
        val canControl = canAsk && store.watchCanControl
        val session = known?.let { WatchSession.fields(it, names, canControl) }.orEmpty()
        // Which device each offered address was, so a removal asked for later
        // cannot take off whoever has the address by then.
        store.offeredMacs = if (known != null && canControl) known.macs.filterKeys { known.removable(it) } else emptyMap()
        val payload = WatchPayload.build(doc, face, now, store.nextSequence(), EVERY_SECONDS, canAsk, session)
        store.lastPush = now.epochSecond
        store.note("watch", Garmin.send(app, payload).joinToString("; "))
    }

    private fun faceOf(reading: Reading?, live: Boolean, now: Instant, why: String?): Face {
        val zone = ZoneId.systemDefault()
        if (reading == null) return Face.unknown(now, zone, why ?: "tap to read", hour24(app))
        val doc = Pipeline.derive(reading, Pipeline.epochSeconds(now) - reading.ts, live, now)
        return Face.of(doc, zone, hour24(app))
    }

    companion object {
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
            // A switch runs soon after it was asked for, or not at all: one the
            // system held back for minutes is one the person has given up on,
            // and may since have done another way.
            val askedAt = inputData.getLong(ASKED_AT, 0)
            if ((action != null || remove != null) && Instant.now().epochSecond - askedAt > SWITCH_LIFETIME_SECONDS) {
                store.note("session", "${action?.name?.lowercase() ?: "remove $remove"} asked ${Instant.now().epochSecond - askedAt}s ago; too late, nothing done")
                QuotaWidget.draw(applicationContext, Refresher.cachedFace(applicationContext).copy(footnote = "too late: nothing done", warning = true))
                return Result.success()
            }
            when {
                action != null -> refresher.switch(action, store.watchEnabled, trigger)
                remove != null -> refresher.removeDevice(remove, store.watchEnabled, trigger)
                inputData.getBoolean(PERIODIC, false) -> {
                    // Screen on: read, as the Tasker tile's profile does, and
                    // send the watch the result too. Screen off: nobody is
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
        const val ASKED_AT = "askedAt"

        /** How long after it was asked for a switch may still run. */
        const val SWITCH_LIFETIME_SECONDS = 180L
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
    fun switch(context: Context, action: SessionAction) {
        val input = Data.Builder().putAll(input(true, false, "widget switch")).putString(QuotaWorker.ACTION, action.name)
            .putLong(QuotaWorker.ASKED_AT, Instant.now().epochSecond).build()
        val request = OneTimeWorkRequestBuilder<QuotaWorker>().setInputData(input).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SWITCH, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * The watch asked to switch data or take a device off. KEEP, as for the
     * widget's switch: a second press while the first is on its way is
     * dropped, and the worker checks either against the portal first.
     */
    fun fromWatch(context: Context, command: WatchCommand) {
        val data = Data.Builder().putAll(input(true, true, "watch")).putLong(QuotaWorker.ASKED_AT, Instant.now().epochSecond)
        when (command) {
            is WatchCommand.Act -> data.putString(QuotaWorker.ACTION, command.action.name)
            is WatchCommand.Remove -> data.putString(QuotaWorker.REMOVE, command.ip)
            WatchCommand.Refresh -> return askedByWatch(context)
        }
        val request = OneTimeWorkRequestBuilder<QuotaWorker>().setInputData(data.build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SWITCH, ExistingWorkPolicy.KEEP, request)
    }

    /** The watch asked for a reading: read now and send it back. */
    fun askedByWatch(context: Context) = enqueue(context, ASKED, true, true, "watch asked", ExistingWorkPolicy.KEEP)

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
