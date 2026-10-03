package io.github.gsfernandes81.zwanaquota

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The Connect IQ companion half: send a reading to the watch app, and say
 * plainly what happened when it could not be sent.
 *
 * Everything here is optional to the app. The widget never touches this
 * object, and nothing calls it unless Garmin Connect is installed
 * ([present]) -- so without it this is a widget and nothing else.
 *
 * Every call blocks, with a timeout, and must be made off the main thread:
 * the SDK answers on the main looper, which is what the waits are for.
 */
object Garmin {
    /**
     * The watch app's id: `id` in garmin/manifest.xml. The two must be the
     * same string or the phone is sending to an app that does not exist.
     */
    const val APP_ID = "8bc64f99960b479b9613957aee9bf73c"

    /** Garmin Connect's package: the SDK's own manifest lets this app see it. */
    private const val CONNECT = "com.garmin.android.apps.connectmobile"

    /**
     * Whether Garmin Connect is installed and not disabled, which is all it
     * takes for the watch to be used: cheap enough to ask every time, and nothing about
     * the SDK, which starts only when there is something to send or hear.
     */
    fun present(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(CONNECT, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private val main = Handler(Looper.getMainLooper())

    /** The SDK's state, in words: `ready`, or why not. */
    @Volatile
    var state: String = "not started"
        private set

    /**
     * The SDK, started once per process and kept while it works. Null, with
     * [state] saying why, when Garmin Connect is missing, too old, or not
     * answering; the next call starts it again.
     */
    @Synchronized
    fun ready(context: Context, timeoutMs: Long = 10_000): ConnectIQ? {
        val app = context.applicationContext
        val iq = ConnectIQ.getInstance(app, ConnectIQ.IQConnectType.WIRELESS)
        // Ready, even if it said so only after the last wait ran out.
        if (state == READY) return iq
        val done = CountDownLatch(1)
        main.post {
            // Undo the last start first, if there was one: each start
            // registers a receiver and binds Garmin Connect anew, and only
            // shutdown gives them back -- in the process the listener keeps
            // alive for days, they would pile up, and each would hand on the
            // watch's messages. It throws when nothing was started. The
            // listeners it drops were dead with the SDK, and WatchListener
            // registers again every quarter of an hour.
            try {
                iq.shutdown(app)
            } catch (_: Exception) {
            }
            try {
                // autoUI false: the SDK would otherwise pop a "get Garmin
                // Connect" dialog, from a background job, at someone who did
                // not ask for a watch.
                iq.initialize(app, false, object : ConnectIQ.ConnectIQListener {
                    override fun onSdkReady() {
                        state = READY
                        done.countDown()
                    }

                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                        state = when (status) {
                            ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> "Garmin Connect is not installed"
                            ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> "Garmin Connect needs updating"
                            else -> "Garmin Connect did not answer (${status?.name}): signed in? battery-restricted?"
                        }
                        done.countDown()
                    }

                    override fun onSdkShutDown() {
                        state = "shut down"
                    }
                })
            } catch (e: Exception) {
                state = "SDK would not start: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
                done.countDown()
            }
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) state = "no answer from Garmin Connect in ${timeoutMs / 1000}s"
        return iq.takeIf { state == READY }
    }

    /**
     * What a [send] did: a line per watch (or one saying why nothing could be
     * tried); whether any watch app got it; and whether Garmin Connect has
     * no watch paired at all, which it knows only once it answers.
     */
    class Sent(val lines: List<String>, val delivered: Boolean, val unpaired: Boolean)

    /** Send [payload] to the watch app on every connected watch. */
    fun send(context: Context, payload: Map<String, Any>): Sent {
        val iq = ready(context) ?: return Sent(listOf(state), delivered = false, unpaired = false)
        val devices = try {
            iq.knownDevices.orEmpty()
        } catch (e: Exception) {
            return Sent(listOf("cannot list watches: ${e.javaClass.simpleName}"), delivered = false, unpaired = false)
        }
        if (devices.isEmpty()) return Sent(listOf("no watch is paired with Garmin Connect"), delivered = false, unpaired = true)
        val app = IQApp(APP_ID)
        var delivered = false
        val lines = devices.map { device ->
            val status = statusOf(iq, device)
            if (status != "CONNECTED") {
                "${device.friendlyName}: not sent, watch is $status"
            } else {
                val outcome = sendTo(iq, device, app, HashMap(payload))
                if (outcome == SENT) delivered = true
                "${device.friendlyName}: $outcome"
            }
        }
        return Sent(lines, delivered, unpaired = false)
    }

    /**
     * Listen for the watch app on every paired watch, calling [asked] (on
     * the main thread) with whatever it sends; [WatchCommand.parse] decides
     * what that is. Registering again replaces the listener, so this
     * is safe to repeat, which is how a watch paired later or a Garmin
     * Connect restart is picked up. Returns what happened, in words.
     */
    fun listen(context: Context, asked: (String, List<Any?>) -> Unit): String {
        val iq = ready(context) ?: return state
        val devices = try {
            iq.knownDevices.orEmpty()
        } catch (e: Exception) {
            return "cannot list watches: ${e.javaClass.simpleName}"
        }
        if (devices.isEmpty()) return "no watch is paired with Garmin Connect"
        val app = IQApp(APP_ID)
        val listener = ConnectIQ.IQApplicationEventListener { device, _, message, status ->
            if (status == ConnectIQ.IQMessageStatus.SUCCESS && !message.isNullOrEmpty()) {
                asked(device?.friendlyName ?: "a watch", message)
            }
        }
        val outcome = AtomicReference<String>()
        val done = CountDownLatch(1)
        main.post {
            outcome.set(
                devices.joinToString("; ") { device ->
                    try {
                        iq.registerForAppEvents(device, app, listener)
                        "${device.friendlyName}: listening"
                    } catch (e: Exception) {
                        "${device.friendlyName}: not listening (${e.javaClass.simpleName})"
                    }
                },
            )
            done.countDown()
        }
        done.await(10, TimeUnit.SECONDS)
        return outcome.get() ?: "no answer registering"
    }

    private fun statusOf(iq: ConnectIQ, device: IQDevice): String = try {
        iq.getDeviceStatus(device)?.name ?: "UNKNOWN"
    } catch (e: Exception) {
        "unknown (${e.javaClass.simpleName})"
    }

    /**
     * On the main thread, as [listen] registers: the SDK keeps a send's
     * listener in maps it reads there, unlocked, as the answer comes in.
     */
    private fun sendTo(iq: ConnectIQ, device: IQDevice, app: IQApp, payload: HashMap<String, Any>): String {
        val answer = AtomicReference("no answer in ${SEND_SECONDS}s")
        val done = CountDownLatch(1)
        main.post {
            try {
                iq.sendMessage(device, app, payload, object : ConnectIQ.IQSendMessageListener {
                    override fun onMessageStatus(device: IQDevice?, app: IQApp?, status: ConnectIQ.IQMessageStatus?) {
                        answer.set(
                            when (status) {
                                ConnectIQ.IQMessageStatus.SUCCESS -> SENT
                                ConnectIQ.IQMessageStatus.FAILURE_DEVICE_NOT_CONNECTED -> "not sent, watch disconnected"
                                ConnectIQ.IQMessageStatus.FAILURE_INVALID_DEVICE -> "not sent, watch app missing? (${status.name})"
                                else -> "not sent: ${status?.name}"
                            },
                        )
                        done.countDown()
                    }
                })
            } catch (e: Exception) {
                answer.set("not sent: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
                done.countDown()
            }
        }
        done.await(SEND_SECONDS, TimeUnit.SECONDS)
        return answer.get()
    }

    private const val READY = "ready"
    private const val SENT = "sent"

    /**
     * How long a send waits for Garmin Connect to say it was delivered. A
     * send waited out may still arrive; this only bounds how long the next
     * message is held behind it (Refresher.push makes them one at a time),
     * and is part of what a watch waiting on an answer waits through: Ask.WAIT
     * for a reading (with one portal timeout) and Ask.WAIT_SWITCH for a switch
     * (the arithmetic is at QuotaWorker.WATCH_SWITCH_SECONDS), both held by
     * tests/test_watch_contract.py.
     */
    const val SEND_SECONDS = 20L
}
