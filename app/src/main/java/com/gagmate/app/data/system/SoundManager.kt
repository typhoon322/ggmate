package com.gagmate.app.data.system

import android.content.Context
import android.media.MediaPlayer
import com.gagmate.app.R

/**
 * Plays short UI sound effects.
 * Resources live in res/raw/ as WAV files.
 */
object SoundManager {

    /** Play the "connected to coffee machine" chime. */
    fun playConnectionSuccess(context: Context) {
        try {
            MediaPlayer.create(context, R.raw.coffee_chime)?.apply {
                setOnCompletionListener { release() }
                start()
            }
        } catch (_: Exception) {
            // Sound is best-effort; never crash for audio
        }
    }
}
