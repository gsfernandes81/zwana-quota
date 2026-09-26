package io.github.gsfernandes81.zwanaquota

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.gsfernandes81.zwanaquota.core.Face
import io.github.gsfernandes81.zwanaquota.core.FallbackTransport
import io.github.gsfernandes81.zwanaquota.core.Pipeline
import io.github.gsfernandes81.zwanaquota.core.PortalClient
import io.github.gsfernandes81.zwanaquota.core.PortalError
import io.github.gsfernandes81.zwanaquota.core.Reading
import io.github.gsfernandes81.zwanaquota.core.Route
import io.github.gsfernandes81.zwanaquota.core.UrlConnectionTransport
import io.github.gsfernandes81.zwanaquota.core.WatchPayload
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

    fun run(force: Boolean, pushWanted: Boolean, trigger: String) {
        val now = Instant.now()
        var reading = store.reading()
        var live = false
        var why: String? = null

        val age = reading?.let { Pipeline.epochSeconds(now) - it.ts }
        if (force || age == null || age > MAX_AGE_SECONDS) {
            if (!vault.signedIn) {
                why = "sign in: open the app"
                store.note("read", "not signed in")
            } else {
                val path = Networks.path(app)
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
                try {
                    reading = client.read(reading?.carry(), now).also(store::save)
                    live = true
                    store.note("read", "ok over $network ($trigger)")
                } catch (e: PortalError) {
                    why = "portal: ${e.message}"
                    store.note("read", "failed over $network: ${e.message}")
                }
            }
        }

        val face = faceOf(reading, live, now, why)
        QuotaWidget.draw(app, face, busy = false)

        if (pushWanted && reading != null) push(reading, live, now)
    }

    private fun push(reading: Reading, live: Boolean, now: Instant) {
        val doc = Pipeline.derive(reading, Pipeline.epochSeconds(now) - reading.ts, live, now)
        val payload = WatchPayload.build(doc, Face.of(doc, ZoneId.systemDefault()), now, store.nextSequence(), EVERY_SECONDS)
        store.note("watch", Garmin.send(app, payload).joinToString("; "))
    }

    private fun faceOf(reading: Reading?, live: Boolean, now: Instant, why: String?): Face {
        val zone = ZoneId.systemDefault()
        if (reading == null) return Face.unknown(now, zone, why ?: "tap to read")
        val doc = Pipeline.derive(reading, Pipeline.epochSeconds(now) - reading.ts, live, now)
        return Face.of(doc, zone)
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

        /** Cached reading, derived as of now: what the widget can draw instantly. */
        fun cachedFace(context: Context): Face {
            val now = Instant.now()
            val reading = Store(context).reading()
                ?: return Face.unknown(
                    now,
                    ZoneId.systemDefault(),
                    if (Vault(context).signedIn) "tap to read" else "sign in: open the app",
                )
            val doc = Pipeline.derive(reading, Pipeline.epochSeconds(now) - reading.ts, false, now)
            return Face.of(doc, ZoneId.systemDefault())
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
    class Path(val route: Route, val label: String, val pinned: ((URL) -> URLConnection)?)

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
            Route.PIN_WIFI -> Path(
                Route.PIN_WIFI,
                "Wi-Fi (pinned: the default route is not Wi-Fi)",
                pinned = { url: URL -> wifi!!.openConnection(url) },
            )
            Route.DEFAULT -> Path(
                Route.DEFAULT,
                when {
                    vpn -> "a VPN (a firewall like GlassWire?)"
                    wifiDefault -> "Wi-Fi"
                    else -> "mobile data (no Wi-Fi)"
                },
                null,
            )
        }
    }
}

/** The one Worker: every refresh, tapped, periodic or pressed, runs through it. */
class QuotaWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = Store(applicationContext)
        val trigger = inputData.getString(TRIGGER) ?: "?"
        try {
            Refresher(applicationContext).run(
                force = inputData.getBoolean(FORCE, false),
                pushWanted = inputData.getBoolean(PUSH, false) || store.watchEnabled,
                trigger = trigger,
            )
            store.note("worker", "ran ($trigger)")
        } catch (e: Exception) {
            // A failure here is drawn and noted, never retried in a loop: the
            // next tap or the next period is the retry.
            store.note("worker", "failed ($trigger): ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
        return Result.success()
    }

    companion object {
        const val FORCE = "force"
        const val PUSH = "push"
        const val TRIGGER = "trigger"
    }
}

/** What is enqueued, and under which name, so two taps are one read. */
object Work {
    private const val REFRESH = "refresh"
    private const val PUSH = "push-now"
    private const val WATCH = "watch-every-30m"

    fun refresh(context: Context, force: Boolean, trigger: String) = enqueue(context, REFRESH, force, false, trigger)

    /** Send to the watch now, whether or not the periodic send is on: the test button. */
    fun pushNow(context: Context) = enqueue(context, PUSH, false, true, "button", ExistingWorkPolicy.REPLACE)

    /** The periodic send runs only while the watch switch is on, and never otherwise. */
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (Store(context).watchEnabled) {
            val request = PeriodicWorkRequestBuilder<QuotaWorker>(
                Refresher.EVERY_MINUTES, TimeUnit.MINUTES,
                10, TimeUnit.MINUTES,
            ).setInputData(input(false, true, "every ${Refresher.EVERY_MINUTES}m")).build()
            manager.enqueueUniquePeriodicWork(WATCH, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            manager.cancelUniqueWork(WATCH)
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
