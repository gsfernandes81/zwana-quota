package io.github.gsfernandes81.zwanaquota

import android.content.Context
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
 * object, and nothing calls it unless "send to watch" is switched on or a
 * button on the settings screen is pressed -- so without Garmin Connect, a
 * paired watch or the watch app, this is a widget and nothing else.
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

    private val main = Handler(Looper.getMainLooper())
    private var instance: ConnectIQ? = null

    /** The SDK's state, in words: `ready`, or why not. */
    @Volatile
    var state: String = "not started"
        private set

    /**
     * The SDK, started once per process and kept. Null, with [state] saying
     * why, when Garmin Connect is missing, too old, or not answering.
     */
    @Synchronized
    fun ready(context: Context, timeoutMs: Long = 10_000): ConnectIQ? {
        if (state == READY) instance?.let { return it }
        val app = context.applicationContext
        val iq = ConnectIQ.getInstance(app, ConnectIQ.IQConnectType.WIRELESS)
        val done = CountDownLatch(1)
        main.post {
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
        instance = iq.takeIf { state == READY }
        return instance
    }

    /**
     * Send [payload] to the watch app on every connected watch. Returns one
     * line per watch saying what happened, or one line saying why nothing
     * could be tried.
     */
    fun send(context: Context, payload: Map<String, Any>): List<String> {
        val iq = ready(context) ?: return listOf(state)
        val devices = try {
            iq.knownDevices.orEmpty()
        } catch (e: Exception) {
            return listOf("cannot list watches: ${e.javaClass.simpleName}")
        }
        if (devices.isEmpty()) return listOf("no watch is paired with Garmin Connect")
        val app = IQApp(APP_ID)
        return devices.map { device ->
            val status = statusOf(iq, device)
            if (status != "CONNECTED") {
                "${device.friendlyName}: not sent, watch is $status"
            } else {
                "${device.friendlyName}: ${sendTo(iq, device, app, HashMap(payload))}"
            }
        }
    }

    /** For the settings screen: every watch, whether it is connected, and whether the watch app is on it. */
    fun check(context: Context): List<String> {
        val iq = ready(context) ?: return listOf("SDK: $state")
        val devices = try {
            iq.knownDevices.orEmpty()
        } catch (e: Exception) {
            return listOf("SDK: $state", "cannot list watches: ${e.javaClass.simpleName}")
        }
        if (devices.isEmpty()) return listOf("SDK: $state", "no watch is paired with Garmin Connect")
        return listOf("SDK: $state") + devices.map { device ->
            "${device.friendlyName}: ${statusOf(iq, device)}, watch app ${appOn(iq, device)}"
        }
    }

    private fun statusOf(iq: ConnectIQ, device: IQDevice): String = try {
        iq.getDeviceStatus(device)?.name ?: "UNKNOWN"
    } catch (e: Exception) {
        "unknown (${e.javaClass.simpleName})"
    }

    private fun appOn(iq: ConnectIQ, device: IQDevice): String {
        val answer = AtomicReference("did not answer")
        val done = CountDownLatch(1)
        try {
            iq.getApplicationInfo(APP_ID, device, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp?) {
                    answer.set("${app?.status?.name ?: "found"}, version ${app?.version()}")
                    done.countDown()
                }

                override fun onApplicationNotInstalled(applicationId: String?) {
                    answer.set("NOT installed (id $applicationId)")
                    done.countDown()
                }
            })
        } catch (e: Exception) {
            return "unknown (${e.javaClass.simpleName})"
        }
        done.await(15, TimeUnit.SECONDS)
        return answer.get()
    }

    private fun sendTo(iq: ConnectIQ, device: IQDevice, app: IQApp, payload: HashMap<String, Any>): String {
        val answer = AtomicReference("no answer in 30s")
        val done = CountDownLatch(1)
        try {
            iq.sendMessage(device, app, payload, object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(device: IQDevice?, app: IQApp?, status: ConnectIQ.IQMessageStatus?) {
                    answer.set(
                        when (status) {
                            ConnectIQ.IQMessageStatus.SUCCESS -> "sent"
                            ConnectIQ.IQMessageStatus.FAILURE_DEVICE_NOT_CONNECTED -> "not sent, watch disconnected"
                            ConnectIQ.IQMessageStatus.FAILURE_INVALID_DEVICE -> "not sent, watch app missing? (${status.name})"
                            else -> "not sent: ${status?.name}"
                        },
                    )
                    done.countDown()
                }
            })
        } catch (e: Exception) {
            return "not sent: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
        }
        done.await(30, TimeUnit.SECONDS)
        return answer.get()
    }

    private const val READY = "ready"
}
