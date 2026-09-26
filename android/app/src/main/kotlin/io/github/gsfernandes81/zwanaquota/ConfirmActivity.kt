package io.github.gsfernandes81.zwanaquota

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.gsfernandes81.zwanaquota.core.Session
import io.github.gsfernandes81.zwanaquota.core.SessionAction

/**
 * The question the widget's switch asks before taking a device off data:
 * a dialog over the home screen, and nothing else. It says who goes off --
 * every device, when this phone switched data on; only this phone, when it
 * joined someone else's -- by the names the widget shows.
 *
 * What it asks about is what the widget was drawn with. The worker checks
 * again against the portal before sending anything, so a "yes" to a picture
 * that has since gone out of date does nothing rather than the wrong thing.
 */
class ConfirmActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        val action = QuotaWidget.actionOf(intent)
        val session = Store(this).session()
        if (action == null || !action.confirm || session == null) {
            finish()
            return
        }
        val names = Store(this).names().filterValues { it.name.isNotEmpty() }.mapValues { it.value.name }
        val others = session.devices(names).filterNot { it.me }.map { it.label }

        val builder = MaterialAlertDialogBuilder(this)
        when (action) {
            SessionAction.TURN_OFF_EVERYWHERE -> if (others.isEmpty()) {
                builder.setTitle(R.string.confirm_off_alone_title)
                    .setMessage(R.string.confirm_off_alone_body)
            } else {
                builder.setTitle(R.string.confirm_off_title)
                    .setMessage(getString(R.string.confirm_off_body, (listOf(Session.THIS_PHONE) + others).joinToString(", ")))
            }.setPositiveButton(R.string.confirm_off_yes) { _, _ -> QuotaWidget.switch(this, action) }
            SessionAction.LEAVE -> builder.setTitle(R.string.confirm_leave_title)
                .setMessage(getString(R.string.confirm_leave_body, others.joinToString(", ").ifEmpty { "the others" }))
                .setPositiveButton(R.string.confirm_leave_yes) { _, _ -> QuotaWidget.switch(this, action) }
            else -> return finish()
        }
        builder.setNegativeButton(R.string.confirm_cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }
}
