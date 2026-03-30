package com.foursightai

import android.view.View
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubtitleManager(private val subtitleTextView: TextView) {

    suspend fun updateSubtitle(text: String) {
        withContext(Dispatchers.Main) {
            subtitleTextView.text = text
            
            // Fade In
            subtitleTextView.alpha = 0f
            subtitleTextView.visibility = View.VISIBLE
            subtitleTextView.animate()
                .alpha(1f)
                .setDuration(250)
                .start()
        }
    }
}
