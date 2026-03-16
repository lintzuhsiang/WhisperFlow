package com.liveTranslation.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.liveTranslation.R

/**
 * A floating overlay window that renders subtitles on top of any other app.
 *
 * Window flags chosen so the video app underneath receives all touch events unimpeded:
 *   • TYPE_APPLICATION_OVERLAY  – modern overlay type (requires SYSTEM_ALERT_WINDOW)
 *   • FLAG_NOT_FOCUSABLE        – overlay never steals keyboard focus
 *   • FLAG_NOT_TOUCH_MODAL      – touches outside the view pass through to the app below
 *   • FLAG_LAYOUT_IN_SCREEN     – position relative to the full screen
 *
 * The view is also draggable so the user can reposition it without going back
 * to the main app.
 */
class FloatingSubtitleView(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Inflated overlay layout (res/layout/floating_subtitle.xml)
    private val overlayView: View =
        LayoutInflater.from(context).inflate(R.layout.floating_subtitle, null)

    private val tvOriginal: TextView   = overlayView.findViewById(R.id.tvOriginal)
    private val tvTranslated: TextView = overlayView.findViewById(R.id.tvTranslated)

    // WindowManager layout params – configured once, updated on drag
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        x = 0
        y = dpToPx(80)   // 80 dp from the bottom edge
    }

    private var isShowing = false

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /** Add the overlay to the screen. Call from the Main thread. */
    fun show() {
        if (!isShowing) {
            attachDragListener()
            windowManager.addView(overlayView, params)
            isShowing = true
        }
    }

    /** Remove the overlay from the screen. Call from the Main thread. */
    fun hide() {
        if (isShowing) {
            windowManager.removeView(overlayView)
            isShowing = false
        }
    }

    /**
     * Update subtitle text.  Must be called from the **Main thread**.
     *
     * In [com.liveTranslation.service.AudioCaptureService] this is dispatched
     * with `withContext(Dispatchers.Main)`.
     *
     * @param originalText   The raw ASR transcript (in source language).
     * @param translatedText The MT output (in target language).
     */
    fun updateSubtitle(originalText: String, translatedText: String) {
        tvOriginal.text   = originalText
        tvTranslated.text = translatedText

        // Make the view visible again if it was hidden after silence
        if (overlayView.visibility != View.VISIBLE) {
            overlayView.visibility = View.VISIBLE
        }
    }

    /** Hide subtitle text when no speech is detected (keeps overlay attached). */
    fun clearSubtitle() {
        tvOriginal.text   = ""
        tvTranslated.text = ""
    }

    // ──────────────────────────────────────────────────────────────────────
    // Drag support
    // ──────────────────────────────────────────────────────────────────────

    private fun attachDragListener() {
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX    = params.x
                    initialY    = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchStartX).toInt()
                    params.y = initialY - (event.rawY - touchStartY).toInt()
                    if (isShowing) windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
