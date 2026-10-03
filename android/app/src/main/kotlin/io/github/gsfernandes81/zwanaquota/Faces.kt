package io.github.gsfernandes81.zwanaquota

import android.content.Context
import io.github.gsfernandes81.zwanaquota.core.Face

/**
 * Everywhere the reading is drawn: the home-screen widget, and the Quick
 * Settings tile with its panel. One call, so a read, a switch or a failure
 * shows on both at once and the two never disagree.
 */
object Faces {
    /** [busy], when given, stands in for the footnote while something is on its way. */
    fun draw(context: Context, face: Face, busy: String? = null) {
        QuotaWidget.draw(context, face, busy)
        QuotaTile.draw(face, busy)
    }
}
