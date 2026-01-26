package com.anthroid.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.anthroid.R
import com.anthroid.main.MainPagerActivity

/**
 * Manages the floating overlay banner for screen automation operations.
 * Shows current operation, stop button when active, close button when inactive.
 */
class ScreenAutomationOverlay(private val context: Context) {

    companion object {
        private const val TAG = "ScreenAutomationOverlay"

        @Volatile
        private var instance: ScreenAutomationOverlay? = null

        // Track if Anthroid app is in foreground - don't show overlay when in foreground
        @Volatile
        var isAppInForeground = false

        fun getInstance(context: Context): ScreenAutomationOverlay {
            return instance ?: synchronized(this) {
                instance ?: ScreenAutomationOverlay(context.applicationContext).also { instance = it }
            }
        }

        fun hasOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var isShowing = false
    private var isActive = true
    private var isCompleted = false  // true = completed successfully, false = interrupted
    private var onStopCallback: (() -> Unit)? = null

    private var overlayContainer: LinearLayout? = null
    private var overlayIcon: ImageView? = null
    private var overlayText: TextView? = null
    private var textScrollView: HorizontalScrollView? = null
    private var scrollAnimator: android.animation.ValueAnimator? = null
    private var stopButton: TextView? = null
    private var closeButton: TextView? = null
    
    // Swipe to dismiss
    private var touchStartY = 0f
    private var touchStartRawY = 0f
    private val swipeThreshold = 40f  // dp threshold for swipe dismiss

    /**
     * Show the overlay with the given operation text.
     * @param operationText The text describing the current operation
     * @param onStop Callback when stop button is clicked
     */
    fun show(operationText: String, onStop: (() -> Unit)? = null) {
        if (!hasOverlayPermission(context)) {
            Log.w(TAG, "No overlay permission, cannot show overlay")
            return
        }

        // Don't show overlay when Anthroid is in foreground
        if (isAppInForeground) {
            Log.d(TAG, "App in foreground, hiding overlay if showing")
            hide()
            return
        }

        handler.post {
            try {
                if (overlayView == null) {
                    createOverlay()
                }

                isActive = true
                onStopCallback = onStop
                updateUI(operationText, isActive = true, isCompleted = false)

                if (!isShowing) {
                    windowManager.addView(overlayView, createLayoutParams())
                    isShowing = true
                }

                Log.i(TAG, "Overlay shown: $operationText")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay", e)
            }
        }
    }

    /**
     * Update the operation text while overlay is showing.
     * Uses custom smooth scrolling animation.
     */
    fun updateText(operationText: String) {
        handler.post {
            if (isShowing) {
                overlayText?.text = operationText
                Log.d(TAG, "updateText: ${operationText.take(40)}...")
                // Start smooth scroll animation after text is measured
                overlayText?.post { startSmoothScroll() }
            }
        }
    }
    
    /**
     * Start smooth scrolling animation for long text.
     * Scrolls at ~60 chars per second for comfortable reading.
     */
    private fun startSmoothScroll() {
        scrollAnimator?.cancel()
        val scrollView = textScrollView ?: return
        val textView = overlayText ?: return
        
        // Wait for layout to complete
        textView.post {
            val textWidth = textView.width
            val scrollWidth = scrollView.width
            val scrollDistance = textWidth - scrollWidth
            
            if (scrollDistance <= 0) {
                // Text fits, no need to scroll
                scrollView.scrollTo(0, 0)
                return@post
            }
            
            // Calculate duration: ~60 chars per second, ~10dp per char
            // So ~600dp per second, or ~1.67ms per dp
            val duration = (scrollDistance * 12L).coerceIn(2000L, 30000L)  // 2-30 seconds
            
            scrollAnimator = android.animation.ValueAnimator.ofInt(0, scrollDistance).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.RESTART
                
                addUpdateListener { animator ->
                    scrollView.scrollTo(animator.animatedValue as Int, 0)
                }
                
                // Pause at the end before restarting
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationRepeat(animation: android.animation.Animator) {
                        // Brief pause at the end
                        handler.postDelayed({
                            scrollView.scrollTo(0, 0)
                        }, 1500)
                    }
                })
                
                // Start with a delay so user can see the beginning
                startDelay = 1000
                start()
            }
        }
    }

    /**
     * Mark the overlay as interrupted/inactive.
     */
    fun setInterrupted() {
        handler.post {
            isActive = false
            isCompleted = false
            updateUI("Operation interrupted", isActive = false, isCompleted = false)
            Log.i(TAG, "Overlay set to interrupted state")
        }
    }

    /**
     * Mark the overlay as completed but keep it visible.
     * Call hide() explicitly when the entire agent session is done.
     */
    fun setCompleted(resultText: String = "Operation completed") {
        handler.post {
            isActive = false
            isCompleted = true
            updateUI(resultText, isActive = false, isCompleted = true)
            // Don't auto-hide - the caller (ClaudeFragment/ViewModel) will hide when session ends
        }
    }

    /**
     * Show question mode - waiting for user answer.
     */
    fun setAskingQuestion(questionText: String = "Waiting for your answer...") {
        handler.post {
            if (isShowing) {
                overlayText?.text = questionText
                stopButton?.text = "❓"
                Log.i(TAG, "Overlay set to question mode")
            }
        }
    }

    /**
     * Hide and remove the overlay.
     */
    fun hide() {
        handler.post {
            try {
                scrollAnimator?.cancel()
                scrollAnimator = null
                if (isShowing && overlayView != null) {
                    windowManager.removeView(overlayView)
                    isShowing = false
                    Log.i(TAG, "Overlay hidden")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide overlay", e)
            }
        }
    }

    private fun createOverlay() {
        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.overlay_screen_automation, null)

        overlayContainer = overlayView?.findViewById(R.id.overlay_container)
        overlayIcon = overlayView?.findViewById(R.id.overlay_icon)
        textScrollView = overlayView?.findViewById(R.id.text_scroll_view)
        overlayText = overlayView?.findViewById(R.id.overlay_text)
        stopButton = overlayView?.findViewById(R.id.overlay_stop_button)
        closeButton = overlayView?.findViewById(R.id.overlay_close_button)

        // Stop button click - interrupt operation
        stopButton?.setOnClickListener {
            Log.i(TAG, "Stop button clicked")
            onStopCallback?.invoke()
            setInterrupted()
        }

        // Close/OK button click - open Anthroid and hide overlay
        closeButton?.setOnClickListener {
            Log.i(TAG, "Close/OK button clicked")
            openAnthroid()
            hide()
        }

        // Click on inactive overlay - open Anthroid
        overlayContainer?.setOnClickListener {
            if (!isActive) {
                Log.i(TAG, "Inactive overlay clicked, opening Anthroid")
                openAnthroid()
                hide()
            }
        }
        
        // Click on text always opens Anthroid (regardless of active/inactive state)
        overlayText?.setOnClickListener {
            Log.i(TAG, "Overlay text clicked, opening Anthroid")
            openAnthroid()
        }
        
        // Swipe up to dismiss
        setupSwipeToDismiss()
    }
    
    private fun setupSwipeToDismiss() {
        overlayContainer?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.y
                    touchStartRawY = event.rawY
                    false  // Let click listener also handle if needed
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - touchStartRawY
                    if (deltaY < -20) {
                        // Moving up - translate the view
                        overlayView?.translationY = deltaY.coerceAtMost(0f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val deltaY = event.rawY - touchStartRawY
                    val density = context.resources.displayMetrics.density
                    val thresholdPx = swipeThreshold * density
                    
                    if (deltaY < -thresholdPx) {
                        // Swipe up exceeded threshold - interrupt and hide
                        Log.i(TAG, "Swipe up detected, interrupting automation")
                        onStopCallback?.invoke()
                        overlayView?.animate()
                            ?.translationY(-overlayView!!.height.toFloat())
                            ?.setDuration(150)
                            ?.withEndAction { 
                                hide()
                                overlayView?.translationY = 0f
                            }
                            ?.start()
                    } else {
                        // Reset position
                        overlayView?.animate()
                            ?.translationY(0f)
                            ?.setDuration(150)
                            ?.start()
                    }
                    
                    // If it was a tap (minimal movement), trigger click
                    if (abs(deltaY) < 10 * density) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateUI(text: String, isActive: Boolean, isCompleted: Boolean = false) {
        overlayText?.text = text
        // Start smooth scroll after text update
        overlayText?.post { startSmoothScroll() }

        if (isActive) {
            // Active state: show stop button, hide close button, red eyes
            overlayIcon?.setImageResource(R.drawable.ic_robot_active)
            stopButton?.visibility = View.VISIBLE
            closeButton?.visibility = View.GONE
            overlayContainer?.setBackgroundColor(0xE0424242.toInt()) // Dark gray
            overlayContainer?.isClickable = false
        } else {
            // Inactive state: hide stop button, show close/OK button, black eyes
            overlayIcon?.setImageResource(R.drawable.ic_robot_inactive)
            stopButton?.visibility = View.GONE
            closeButton?.visibility = View.VISIBLE
            // Show "OK" for completed, "✕" for interrupted
            closeButton?.text = if (isCompleted) "👌" else "✕"
            overlayContainer?.setBackgroundColor(0xE0757575.toInt()) // Lighter gray
            overlayContainer?.isClickable = true
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0 // At the top of screen
        }
    }

    private fun openAnthroid() {
        try {
            val intent = Intent(context, MainPagerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Anthroid", e)
        }
    }
}
