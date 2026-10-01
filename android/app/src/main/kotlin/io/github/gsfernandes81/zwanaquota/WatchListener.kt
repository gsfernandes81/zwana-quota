package io.github.gsfernandes81.zwanaquota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.gsfernandes81.zwanaquota.core.WatchCommand

/**
 * Listens for the watch asking for a reading, and answers with one.
 *
 * Garmin Connect hands a watch's message only to a companion that is running
 * and listening: Garmin's way of waking one that is not ("binder service")
 * needs Garmin's approval. So while "Let the watch ask for a reading" is on,
 * this keeps the app running as a foreground service -- which Android shows
 * as a notification, silent and at the lowest importance, and on Android 13
 * and later not at all unless the app is allowed to post notifications.
 *
 * It runs only while both watch switches are on, and says so on the watch:
 * the payload's `ask` is what makes the watch offer to ask, so a watch never
 * offers something nobody is listening for.
 */
class WatchListener : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val relisten = object : Runnable {
        override fun run() {
            listen("every ${RELISTEN_MINUTES}m")
            main.postDelayed(this, RELISTEN_MINUTES * 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = Store(this)
        // Into the foreground first, whatever happens next: a service started
        // with startForegroundService that does not get there in time is an
        // app crash, even one that was about to stop itself.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            store.note("listener", "could not start: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
            stopSelf()
            return START_NOT_STICKY
        }
        if (!wanted(store)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            // Registering blocks on Garmin Connect, so not on the main thread;
            // and again every so often, for a watch paired later or a Garmin
            // Connect that restarted and forgot this app.
            main.postDelayed(relisten, 0)
        }
        return START_STICKY
    }

    /** The last outcome of [listen], so a repeat that changed nothing is not journalled. */
    @Volatile
    private var lastOutcome: String? = null

    private fun listen(why: String) {
        Thread {
            // The SDK calls back on the main thread; the journal is file I/O.
            val outcome = Garmin.listen(this) { watch, message -> Thread { asked(watch, message) }.start() }
            if (outcome != lastOutcome) Store(this).note("listener", "$outcome ($why)")
            lastOutcome = outcome
        }.start()
    }

    private fun asked(watch: String, message: List<Any?>) {
        val store = Store(this)
        if (!wanted(store)) return
        val command = WatchCommand.parse(message) ?: return store.note("listener", "$watch sent something unrecognised; ignored")
        when (command) {
            WatchCommand.Refresh -> {
                // Every ask is a read, or is answered by the read already
                // going (Work.askedByWatch). No time gap: one left unanswered
                // would only tell the wearer "no answer from phone".
                store.note("listener", "$watch asked for a reading")
                Work.askedByWatch(this)
            }
            else -> {
                // Switching and removing only with their own setting on. The
                // watch only offers them then, so this refuses a stale watch
                // that still thinks it may.
                if (!store.watchCanControl) return store.note("listener", "$watch asked to $command; not allowed in settings")
                store.note("listener", "$watch asked to $command")
                Work.fromWatch(this, command)
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(relisten)
        running = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.listener_channel), NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle(getString(R.string.listener_title))
            .setContentText(getString(R.string.listener_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "watch-listener"
        private const val NOTIFICATION_ID = 7
        private const val RELISTEN_MINUTES = 15L

        /** In this process; false after the process was killed, which is what [sync] needs to know. */
        @Volatile
        var running = false
            private set

        @Volatile
        private var lastRefusal = 0L

        private fun wanted(store: Store) = store.watchEnabled && store.watchCanAsk

        /**
         * Start the listener if it should be running, stop it if not. Android
         * allows the start from the settings screen, at boot and after an
         * update, and from the background when the app's battery use is
         * unrestricted; anywhere else it may refuse, which is noted.
         */
        fun sync(context: Context, why: String) {
            val app = context.applicationContext
            val store = Store(app)
            val intent = Intent(app, WatchListener::class.java)
            if (!wanted(store)) {
                if (running) app.stopService(intent)
                return
            }
            if (running) return
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                // Refused from the background (battery-restricted): noted once
                // an hour at most, not on every periodic run.
                val now = System.currentTimeMillis()
                if (now - lastRefusal > 3_600_000) {
                    lastRefusal = now
                    store.note("listener", "not started ($why): ${e.javaClass.simpleName}; set battery use to Unrestricted")
                }
            }
        }
    }
}

/** Starts the listener again after the phone restarts or the app is updated. */
class ListenerRestart : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WatchListener.sync(context, intent.action!!.substringAfterLast('.').lowercase())
            Work.schedule(context)
        }
    }
}
