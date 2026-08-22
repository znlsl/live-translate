package com.livetranslate.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.livetranslate.app.R
import com.livetranslate.app.data.UserSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating subtitle window using classic Views (reliable with WindowManager).
 * - top control strip (grabber + close button): shown for 5s on start, then
 *   collapses away (its space is freed too); tap anywhere on the captions —
 *   a real tap, not a scroll — to pop it back out; auto-hides after 5s idle
 * - corner arc handle to resize box only (font size unchanged)
 * - clamps size/position on orientation change so the handle never goes off-screen
 * - bilingual: source + divider + translation, each with independent auto-scroll
 * - translation-only: single auto-scrolling pane
 * - same-language (input already equals target): force a single pane even if
 *   bilingual is enabled
 */
class SubtitleOverlayController(
    private val context: Context,
    private val onGeometryChanged: (x: Int, y: Int, widthDp: Int, heightDp: Int) -> Unit,
    private val onCloseRequested: () -> Unit = {},
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var inputView: TextView? = null
    private var outputView: TextView? = null
    private var inputScroll: ScrollView? = null
    private var outputScroll: ScrollView? = null
    private var dividerView: View? = null
    private var inputSection: LinearLayout? = null
    private var container: LinearLayout? = null
    private var grabberRow: FrameLayout? = null
    private var closeButton: View? = null

    /** Top control strip (grabber + close): reveal on tap, auto-hide when idle. */
    private val controlsHandler = Handler(Looper.getMainLooper())
    private var controlsShown = false
    private var grabberRowHeightPx = 0
    private var rowAnimator: ValueAnimator? = null

    /**
     * Set when a touch began on an interactive child (grabber / resize handle /
     * close). A quick press-release there must not toggle the strip away — the
     * user was probably starting a drag.
     */
    private var suppressNextToggle = false

    private var settings: UserSettings = UserSettings()
    private var sameLanguageMode: Boolean = false
    private var inputText: String = ""
    private var outputText: String = ""

    /** Last known layout line counts — scroll only when a new line is completed. */
    private var lastInputLineCount = 0
    private var lastOutputLineCount = 0

    private var lastScreenW = 0
    private var lastScreenH = 0

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            clampAndApply(persist = true, reason = "config")
        }

        override fun onLowMemory() = Unit
    }

    private var callbacksRegistered = false

    fun show(initial: UserSettings) {
        if (rootView != null) {
            updateSettings(initial)
            clampAndApply(persist = true, reason = "show-update")
            return
        }
        settings = initial

        val (screenW, screenH, density) = screenMetrics()
        lastScreenW = screenW
        lastScreenH = screenH

        val widthPx = clampWidth((initial.overlayWidthDp * density).roundToInt(), screenW)
        val heightPx = clampHeight((initial.overlayHeightDp * density).roundToInt(), screenH)
        val x = if (initial.overlayX < 0) {
            ((screenW - widthPx) / 2).coerceAtLeast(0)
        } else {
            safeCoerce(initial.overlayX, 0, max(0, screenW - widthPx))
        }
        val y = if (initial.overlayY < 0) {
            (screenH * 0.72f).roundToInt().let { safeCoerce(it, 0, max(0, screenH - heightPx)) }
        } else {
            safeCoerce(initial.overlayY, 0, max(0, screenH - heightPx))
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        layoutParams = params

        val view = buildOverlayView(density)
        rootView = view
        windowManager.addView(view, params)
        registerCallbacks()
        applySettingsToViews()
        applyTranscriptsToViews()
        persistGeometry()
        // Control strip greets the user for 5s, then tucks itself away.
        revealControlsTemporarily()
    }

    fun updateSettings(value: UserSettings) {
        val bilingualChanged = value.bilingual != settings.bilingual
        val fontChanged = value.fontSizeSp != settings.fontSizeSp
        settings = value
        applySettingsToViews()
        applyLayoutMode()
        if (bilingualChanged || fontChanged) {
            // Layout geometry of lines changes — re-baseline counters
            lastInputLineCount = 0
            lastOutputLineCount = 0
        }
        clampAndApply(persist = false, reason = "settings")
        // After mode switch, re-apply text + scroll policy
        applyTranscriptsToViews()
    }

    /**
     * When the detected input language is already the target, collapse to one
     * caption line. Does not persist the bilingual setting.
     */
    fun setSameLanguageMode(enabled: Boolean) {
        if (sameLanguageMode == enabled) return
        sameLanguageMode = enabled
        lastInputLineCount = 0
        lastOutputLineCount = 0
        applyLayoutMode()
        applyTranscriptsToViews()
    }

    fun updateTranscripts(input: String?, output: String?) {
        if (input != null) inputText = input
        if (output != null) outputText = output
        applyTranscriptsToViews()
    }

    fun hide() {
        unregisterCallbacks()
        controlsHandler.removeCallbacksAndMessages(null)
        controlsShown = false
        rowAnimator?.cancel()
        rowAnimator = null
        grabberRow?.animate()?.cancel()
        val view = rootView ?: return
        runCatching { windowManager.removeView(view) }
        rootView = null
        layoutParams = null
        inputView = null
        outputView = null
        inputScroll = null
        outputScroll = null
        dividerView = null
        inputSection = null
        container = null
        grabberRow = null
        closeButton = null
        sameLanguageMode = false
        inputText = ""
        outputText = ""
        lastInputLineCount = 0
        lastOutputLineCount = 0
    }

    /**
     * A tap on the window toggles the strip: pop it out when hidden, tuck it
     * away immediately when shown (no need to wait out the 5s timer).
     */
    private fun onOverlayTapped() {
        if (suppressNextToggle) {
            suppressNextToggle = false
            return
        }
        if (controlsShown) {
            controlsHandler.removeCallbacks(hideControlsRunnable)
            hideControlsNow()
        } else {
            revealControlsTemporarily()
        }
    }

    /**
     * Reveal the control strip — it slides down out of the window's top edge and
     * its space is added back to the caption area — then auto-hide after 5s idle.
     */
    private fun revealControlsTemporarily() {
        if (rootView == null) return
        if (!controlsShown) {
            controlsShown = true
            val row = grabberRow ?: return
            cancelRowAnimations(row)
            row.isEnabled = true
            row.visibility = View.VISIBLE
            val lp = row.layoutParams as? LinearLayout.LayoutParams ?: return
            val startH = if (row.height > 0) row.height.coerceAtMost(grabberRowHeightPx) else 0
            lp.height = startH
            row.layoutParams = lp
            if (startH < grabberRowHeightPx) {
                row.alpha = if (startH == 0) 0f else row.alpha
                row.animate().alpha(1f).setDuration(150).start()
                rowAnimator = ValueAnimator.ofInt(startH, grabberRowHeightPx).apply {
                    duration = 200
                    addUpdateListener { a ->
                        lp.height = a.animatedValue as Int
                        row.layoutParams = lp
                    }
                    start()
                }
            }
        }
        scheduleControlsHide()
    }

    /** Keep the strip visible while the user is actively dragging it. */
    private fun holdControlsVisible() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        if (!controlsShown) {
            controlsShown = true
            val row = grabberRow ?: return
            cancelRowAnimations(row)
            row.isEnabled = true
            row.visibility = View.VISIBLE
            row.alpha = 1f
            (row.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.height = grabberRowHeightPx
                row.layoutParams = lp
            }
        }
    }

    private fun scheduleControlsHide() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
    }

    private fun cancelRowAnimations(row: View) {
        rowAnimator?.cancel()
        rowAnimator = null
        row.animate().cancel()
    }

    private val hideControlsRunnable = Runnable { hideControlsNow() }

    private fun hideControlsNow() {
        val row = grabberRow ?: return
        controlsShown = false
        cancelRowAnimations(row)
        val lp = row.layoutParams as? LinearLayout.LayoutParams ?: return
        val startH = row.height.coerceIn(0, grabberRowHeightPx)
        row.animate().alpha(0f).setDuration(140).start()
        rowAnimator = ValueAnimator.ofInt(startH, 0).apply {
            duration = 200
            addUpdateListener { a ->
                lp.height = a.animatedValue as Int
                row.layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!controlsShown) {
                        // GONE frees the strip's space for the captions. Disabled
                        // so the hidden strip never swallows caption drags.
                        row.isEnabled = false
                        row.visibility = View.GONE
                        row.alpha = 1f
                        lp.height = grabberRowHeightPx
                        row.layoutParams = lp
                    }
                    rowAnimator = null
                }
            })
            start()
        }
    }

    private fun clampAndApply(persist: Boolean, reason: String) {
        val params = layoutParams ?: return
        val view = rootView ?: return
        val (screenW, screenH, _) = screenMetrics()

        val screenChanged = screenW != lastScreenW || screenH != lastScreenH
        lastScreenW = screenW
        lastScreenH = screenH

        val oldW = params.width
        val oldH = params.height
        val oldX = params.x
        val oldY = params.y

        params.width = clampWidth(params.width, screenW)
        params.height = clampHeight(params.height, screenH)
        params.x = safeCoerce(params.x, 0, max(0, screenW - params.width))
        params.y = safeCoerce(params.y, 0, max(0, screenH - params.height))

        val changed = params.width != oldW || params.height != oldH ||
            params.x != oldX || params.y != oldY || screenChanged

        if (changed) {
            Log.i(
                TAG,
                "clamp($reason): ${oldW}x${oldH}@${oldX},${oldY} -> " +
                    "${params.width}x${params.height}@${params.x},${params.y} screen=${screenW}x${screenH}",
            )
            runCatching { windowManager.updateViewLayout(view, params) }
                .onFailure { Log.e(TAG, "updateViewLayout failed", it) }
            if (persist || screenChanged) {
                persistGeometry()
            }
        }
    }

    private fun screenMetrics(): Triple<Int, Int, Float> {
        val density = context.resources.displayMetrics.density
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), density)
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val real = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(real)
            Triple(real.widthPixels, real.heightPixels, density)
        }
    }

    private fun clampWidth(width: Int, screenW: Int): Int {
        val minW = min(MIN_WIDTH_PX, screenW)
        val maxW = max(minW, screenW - EDGE_MARGIN_PX)
        return safeCoerce(width, minW, maxW)
    }

    private fun clampHeight(height: Int, screenH: Int): Int {
        val minH = min(MIN_HEIGHT_PX, screenH)
        val maxH = max(minH, min(screenH / 2, screenH - EDGE_MARGIN_PX))
        return safeCoerce(height, minH, maxH)
    }

    private fun safeCoerce(value: Int, start: Int, end: Int): Int {
        if (end < start) return start
        return value.coerceIn(start, end)
    }

    private fun registerCallbacks() {
        if (callbacksRegistered) return
        runCatching { context.applicationContext.registerComponentCallbacks(configCallbacks) }
        callbacksRegistered = true
    }

    private fun unregisterCallbacks() {
        if (!callbacksRegistered) return
        runCatching { context.applicationContext.unregisterComponentCallbacks(configCallbacks) }
        callbacksRegistered = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayView(density: Float): View {
        // Root detects taps anywhere in the window (including areas the caption
        // ScrollViews consume) so the control strip toggles with a tap, while
        // scrolls/drags still pass through untouched.
        val root = TapDetectLayout(context) { onOverlayTapped() }

        val bg = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(Color.argb((settings.backgroundAlpha * 255).toInt().coerceIn(25, 242), 0, 0, 0))
        }
        root.background = bg
        val padH = (10 * density).roundToInt()
        val padV = (6 * density).roundToInt()
        root.setPadding(padH, padV, padH, padH)
        // The resize handle tucks into the padding to hug the corner; without
        // this it would be clipped away (clipToPadding defaults to true).
        root.clipToPadding = false

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        container = column

        // Thin grabber row: drag anywhere on the row to move; close button at the
        // far right. The whole strip reveals on tap and auto-hides when idle.
        val rowHeight = (22 * density).roundToInt()
        grabberRowHeightPx = rowHeight
        val grabberRow = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                rowHeight,
            )
        }
        this.grabberRow = grabberRow
        val grabberBar = View(context).apply {
            val w = (36 * density).roundToInt()
            val h = (4 * density).roundToInt()
            layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            background = GradientDrawable().apply {
                cornerRadius = 2 * density
                setColor(Color.argb(115, 255, 255, 255))
            }
        }
        grabberRow.addView(grabberBar)

        val close = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                (26 * density).roundToInt(),
                rowHeight,
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
            text = "×"
            textSize = 15f
            setTextColor(Color.argb(190, 255, 255, 255))
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.overlay_close)
            setOnClickListener {
                suppressNextToggle = true
                onCloseRequested()
            }
        }
        closeButton = close
        grabberRow.addView(close)

        grabberRow.setOnTouchListener(MoveTouchListener())
        column.addView(grabberRow)

        // ---- Source pane (bilingual only) ----
        val sourceSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            visibility = if (effectiveBilingual()) View.VISIBLE else View.GONE
        }
        inputSection = sourceSection

        val inScroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // Don't steal drag from grabber/resize; text area is display-only scroll
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        inputScroll = inScroll

        val input = TextView(context).apply {
            setTextColor(Color.argb(200, 255, 255, 255))
            typeface = Typeface.DEFAULT
            setLineSpacing(0f, 1.15f)
            // No maxLines — grow and scroll
            text = ""
        }
        inputView = input
        inScroll.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        sourceSection.addView(inScroll)
        column.addView(sourceSection)

        // Divider between source and translation
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                max(1, (1 * density).roundToInt()),
            ).apply {
                topMargin = (4 * density).roundToInt()
                bottomMargin = (4 * density).roundToInt()
            }
            setBackgroundColor(Color.argb(70, 255, 255, 255))
            visibility = if (effectiveBilingual()) View.VISIBLE else View.GONE
        }
        dividerView = divider
        column.addView(divider)

        // ---- Translation pane (always) ----
        val outScroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        outputScroll = outScroll

        val output = TextView(context).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT
            setLineSpacing(0f, 1.15f)
            text = "…"
        }
        outputView = output
        outScroll.addView(
            output,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        column.addView(outScroll)

        root.addView(column)

        // Resize handle: a short arc hugging the window's bottom-right corner
        // radius. Larger touch target than the visible arc.
        val handleSize = (22 * density).roundToInt()
        val handle = ArcHandleView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                handleSize,
                handleSize,
                Gravity.BOTTOM or Gravity.END,
            ).apply {
                // Nudge the handle halfway into the root's padding so the arc
                // sits closer to the corner without crowding the edge.
                rightMargin = -(padH / 2)
                bottomMargin = -(padH / 2)
            }
        }
        handle.setOnTouchListener(ResizeTouchListener())
        root.addView(handle)

        applyLayoutMode()
        return root
    }

    /**
     * Root container that reports real taps anywhere in the window. Watching at
     * the dispatch level (instead of click listeners) means taps still register
     * on areas consumed by the caption ScrollViews, while scrolls and drags —
     * anything moving past the touch slop — never trigger the reveal.
     */
    private class TapDetectLayout(
        context: Context,
        private val onSingleTap: () -> Unit,
    ) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    downAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val quick = SystemClock.uptimeMillis() - downAt <= TAP_TIMEOUT_MS
                    if (quick && dx * dx + dy * dy <= slop * slop) {
                        onSingleTap()
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        companion object {
            private const val TAP_TIMEOUT_MS = 400L
        }
    }

    /**
     * Draws a thin quarter-arc parallel to the window's bottom-right rounded
     * corner — a subtle "drag diagonally" affordance instead of a big dot.
     */
    private class ArcHandleView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(165, 255, 255, 255)
            strokeWidth = 2.2f * density
        }
        private val cornerRadius = 12f * density
        private val arcRadius = 8.5f * density

        override fun onDraw(canvas: Canvas) {
            // Arc concentric with the window corner: center sits one corner-radius
            // in from this view's bottom-right corner.
            val cx = width - cornerRadius
            val cy = height - cornerRadius
            val rect = RectF(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
            canvas.drawArc(rect, 0f, 90f, false, paint)
        }
    }

    private fun effectiveBilingual(): Boolean = settings.bilingual && !sameLanguageMode

    /** Switch bilingual (split + divider) vs translation-only (full height scroll). */
    private fun applyLayoutMode() {
        val bilingual = effectiveBilingual()
        inputSection?.visibility = if (bilingual) View.VISIBLE else View.GONE
        dividerView?.visibility = if (bilingual) View.VISIBLE else View.GONE

        // When translation-only, output takes all remaining weight.
        // When bilingual, both panes share weight 1f each.
        (inputSection?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = 1f
            lp.height = 0
            inputSection?.layoutParams = lp
        }
        (outputScroll?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.weight = 1f
            lp.height = 0
            outputScroll?.layoutParams = lp
        }
    }

    private fun applySettingsToViews() {
        val alpha = (settings.backgroundAlpha * 255).toInt().coerceIn(25, 242)
        (rootView?.background as? GradientDrawable)?.setColor(Color.argb(alpha, 0, 0, 0))
        inputView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp * 0.9f)
        outputView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp)
    }

    private fun applyTranscriptsToViews() {
        val inTv = inputView
        val outTv = outputView

        if (inTv != null) {
            val next = inputText
            if (inTv.text?.toString() != next) {
                inTv.text = next
                // Only scroll after layout, and only when line count increases
                scheduleLineScroll(inTv, inputScroll, isInput = true)
            }
        }
        if (outTv != null) {
            val next = outputText.ifBlank { "…" }
            if (outTv.text?.toString() != next) {
                outTv.text = next
                scheduleLineScroll(outTv, outputScroll, isInput = false)
            }
        }
    }

    /**
     * Auto-scroll policy: do NOT chase every character.
     * Only when the TextView's laid-out line count increases (a line filled and wrapped),
     * scroll so the newest line is visible — typically one line height at a time.
     *
     * When the display buffer is truncated from the head (long session), the laid-out
     * line count briefly drops. We must NOT snap to top in that case — that is what
     * makes the caption "jump" up and down. Only a real reset (text replaced with
     * something much shorter) snaps to top; truncation keeps the view anchored at the
     * bottom so the newest line stays visible.
     */
    private fun scheduleLineScroll(
        textView: TextView,
        scrollView: ScrollView?,
        isInput: Boolean,
    ) {
        if (scrollView == null) return
        textView.post {
            val layout = textView.layout
            val lineCount = when {
                layout != null && layout.lineCount > 0 -> layout.lineCount
                else -> textView.lineCount
            }.coerceAtLeast(0)

            val previous = if (isInput) lastInputLineCount else lastOutputLineCount
            val textLen = textView.text?.length ?: 0

            when (scrollAction(lineCount, previous, textLen)) {
                ScrollAction.ResetToTop -> {
                    if (isInput) lastInputLineCount = lineCount else lastOutputLineCount = lineCount
                    scrollView.scrollTo(0, 0)
                }
                ScrollAction.HoldSteady -> {
                    // Line count did not increase — keep eyes steady, no scroll.
                }
                ScrollAction.ShowLastLine -> {
                    if (isInput) lastInputLineCount = lineCount else lastOutputLineCount = lineCount
                    scrollToShowLastLine(textView, scrollView)
                }
            }
        }
    }

    private fun scrollToShowLastLine(textView: TextView, scrollView: ScrollView) {
        scrollView.post {
            val layout = textView.layout ?: return@post
            if (layout.lineCount <= 0) return@post
            val last = layout.lineCount - 1
            val lineBottom = layout.getLineBottom(last)
            val target = (lineBottom + scrollView.paddingBottom - scrollView.height)
                .coerceAtLeast(0)
            // Smooth-ish step: if we only grew by 1 line, this moves ~one line
            if (target != scrollView.scrollY) {
                scrollView.smoothScrollTo(0, target)
            }
        }
    }

    private fun persistGeometry() {
        val params = layoutParams ?: return
        val d = context.resources.displayMetrics.density.coerceAtLeast(0.5f)
        runCatching {
            onGeometryChanged(
                params.x,
                params.y,
                (params.width / d).roundToInt().coerceAtLeast(1),
                (params.height / d).roundToInt().coerceAtLeast(1),
            )
        }.onFailure { Log.e(TAG, "persistGeometry failed", it) }
    }

    private inner class MoveTouchListener : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            val root = rootView ?: return false
            return try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        holdControlsVisible()
                        suppressNextToggle = true
                        clampAndApply(persist = false, reason = "move-down")
                        lastX = event.rawX
                        lastY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        lastX = event.rawX
                        lastY = event.rawY
                        val (screenW, screenH, _) = screenMetrics()
                        params.width = clampWidth(params.width, screenW)
                        params.height = clampHeight(params.height, screenH)
                        params.x = safeCoerce(
                            params.x + dx.roundToInt(),
                            0,
                            max(0, screenW - params.width),
                        )
                        params.y = safeCoerce(
                            params.y + dy.roundToInt(),
                            0,
                            max(0, screenH - params.height),
                        )
                        windowManager.updateViewLayout(root, params)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        clampAndApply(persist = true, reason = "move-up")
                        // Persist once per gesture. Writing DataStore on MOVE
                        // retriggers settings collection and fights the drag.
                        persistGeometry()
                        scheduleControlsHide()
                        true
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "move touch failed", t)
                clampAndApply(persist = true, reason = "move-error")
                true
            }
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            val root = rootView ?: return false
            return try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        suppressNextToggle = true
                        clampAndApply(persist = false, reason = "resize-down")
                        lastX = event.rawX
                        lastY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        lastX = event.rawX
                        lastY = event.rawY
                        val (screenW, screenH, _) = screenMetrics()
                        val maxW = max(MIN_WIDTH_PX, screenW - params.x - EDGE_MARGIN_PX)
                        val maxH = max(
                            MIN_HEIGHT_PX,
                            min(screenH / 2, screenH - params.y - EDGE_MARGIN_PX),
                        )
                        params.width = safeCoerce(params.width + dx.roundToInt(), MIN_WIDTH_PX, maxW)
                        params.height = safeCoerce(params.height + dy.roundToInt(), MIN_HEIGHT_PX, maxH)
                        params.x = safeCoerce(params.x, 0, max(0, screenW - params.width))
                        params.y = safeCoerce(params.y, 0, max(0, screenH - params.height))
                        windowManager.updateViewLayout(root, params)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        clampAndApply(persist = true, reason = "resize-up")
                        persistGeometry()
                        true
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "resize touch failed", t)
                clampAndApply(persist = true, reason = "resize-error")
                true
            }
        }
    }

    /**
     * Decision for [scheduleLineScroll] based on how the laid-out line count and
     * text length changed since the last update.
     */
    enum class ScrollAction { ResetToTop, HoldSteady, ShowLastLine }

    companion object {
        private const val TAG = "SubtitleOverlay"
        private const val MIN_WIDTH_PX = 200
        private const val MIN_HEIGHT_PX = 80
        private const val EDGE_MARGIN_PX = 8
        private const val CONTROLS_AUTO_HIDE_MS = 5_000L

        /**
         * Pure decision: given the current line count, the previous one, and the
         * current text length, decide how to scroll.
         *
         * - Lines grew → show the newest line.
         * - Lines shrank because the head was truncated (text is still long) → keep
         *   anchored at the bottom (ShowLastLine), NOT top. This is the fix for the
         *   ~7-minute "jumping up and down" bug: head truncation used to snap to top.
         * - Lines shrank to almost nothing → real reset (clear / mode switch) → top.
         * - No change → hold steady.
         */
        fun scrollAction(
            lineCount: Int,
            previousLineCount: Int,
            textLength: Int,
        ): ScrollAction {
            if (lineCount > previousLineCount) return ScrollAction.ShowLastLine
            if (lineCount == previousLineCount) return ScrollAction.HoldSteady
            // lineCount < previousLineCount: distinguish truncation from reset.
            return if (lineCount <= 1 && textLength < 32) {
                ScrollAction.ResetToTop
            } else {
                // Truncation dropped a few lines from the head but text is still
                // substantial — stay anchored to the newest content.
                ScrollAction.ShowLastLine
            }
        }
    }
}
