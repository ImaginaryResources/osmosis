package dev.konraditurbe.osmosis.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * A list row whose card slides left to uncover actions underneath. Child 0 is the action tray: the
 * darkened pill, the size of the row, with the action tiles packed against its end. Child 1 is the
 * card on top of it.
 *
 * A sideways drag moves the card, rubber-banding a little past either end; letting go snaps it open
 * on a leftward fling or once it's past halfway, shut otherwise. Open, the card stops one tray padding
 * short of the first tile, and each tile grows in as it's uncovered. A tap on an open card closes the
 * row instead of reaching the card. Vertical drags are never claimed, so the list still scrolls.
 */
class SwipeRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        /** A drag on this row began: the moment to close any other open row. */
        fun onSwipeStart()
        /** The row settled open or shut. Not called for [snap]. */
        fun onOpenChanged(open: Boolean)
    }

    var listener: Listener? = null

    /** Off while a GPS-sync link owns the BLE; turning it off also closes the row. */
    var swipeEnabled = true
        set(value) {
            field = value
            if (!value && isOpen) close()
        }

    var isOpen = false
        private set

    /** True while a finger is moving the card; a rebind must leave such a row alone. */
    var isDragging = false
        private set

    private lateinit var tray: ViewGroup
    private lateinit var card: View
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = FLING_DP_PER_S * density
    private val overdrag = OVERDRAG_DP * density
    private val liftZ = LIFT_DP * density
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragStartX = 0f
    private var dragStartOffset = 0f
    private var tapToClose = false
    private var animating = false
    // Set by dismiss() until the next snap(): the row is on its way out, so it takes no touches and
    // layout passes must not pull the card back into view before the list removes it.
    private var dismissing = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        tray = getChildAt(0) as ViewGroup
        card = getChildAt(1)
    }

    fun close() = slideTo(false)

    /** Jump straight to [open], with no animation and no [Listener] call: for a rebind. */
    fun snap(open: Boolean) {
        stopAnimations()
        isDragging = false
        dismissing = false
        isOpen = open
        tray.alpha = 1f
        card.translationX = if (open) -revealWidth() else 0f
        card.translationZ = if (open) liftZ else 0f
        syncTray()
    }

    /** Slide the card off the left edge and fade the tray, then run [then]: the exit before the row is removed. */
    fun dismiss(then: () -> Unit) {
        stopAnimations()
        isOpen = false
        dismissing = true
        animating = true
        tray.animate().alpha(0f).setDuration(DISMISS_MS).start()
        card.animate().translationX(-DISMISS_TRAVEL * width).translationZ(0f)
            .setDuration(DISMISS_MS).setInterpolator(AccelerateInterpolator())
            .withEndAction { animating = false; then() }
            .start()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        velocity?.addMovement(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dismissing) return true
        if (!swipeEnabled || revealWidth() == 0f) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                // On an open row the card is ours: a tap on it closes the row rather than connecting.
                val cardLeft = card.left + card.translationX
                tapToClose = isOpen && ev.x >= cardLeft && ev.x <= cardLeft + card.width
                if (tapToClose) stopAnimations()
                return tapToClose
            }
            MotionEvent.ACTION_MOVE -> if (isSideways(ev)) {
                startDrag(ev)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dismissing) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return tapToClose
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && isSideways(ev)) startDrag(ev)
                if (isDragging) setOffset(rubberBand(dragStartOffset + ev.x - dragStartX))
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) settle() else if (tapToClose) performClick()
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                // The list took the gesture over (a vertical scroll): land wherever the row was headed.
                if (isDragging) settle() else if (tapToClose) slideTo(isOpen)
                endGesture()
            }
        }
        return true
    }

    /** The row's own click is the tap that closes it when open (the card has its own click listener). */
    override fun performClick(): Boolean {
        super.performClick()
        if (isOpen) close()
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // A rebind, or a tile appearing or going, moves where "open" is: follow it, unless a finger or an
        // animation owns the card.
        if (!isDragging && !animating && !dismissing) {
            card.translationX = if (isOpen) -revealWidth() else 0f
            syncTray()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        velocity?.recycle()
        velocity = null
    }

    private fun isSideways(ev: MotionEvent): Boolean {
        val dx = abs(ev.x - downX)
        return dx > touchSlop && dx > abs(ev.y - downY)
    }

    private fun startDrag(ev: MotionEvent) {
        isDragging = true
        stopAnimations()
        dragStartX = ev.x
        dragStartOffset = card.translationX
        parent?.requestDisallowInterceptTouchEvent(true)
        listener?.onSwipeStart()
        card.animate().translationZ(liftZ).setDuration(LIFT_MS).setInterpolator(SETTLE).start()
    }

    private fun settle() {
        val vx = velocity?.let { it.computeCurrentVelocity(1000); it.xVelocity } ?: 0f
        val shouldOpen = when {
            vx <= -flingVelocity -> true
            vx >= flingVelocity -> false
            else -> card.translationX < -revealWidth() / 2
        }
        isDragging = false
        slideTo(shouldOpen)
    }

    private fun endGesture() {
        isDragging = false
        tapToClose = false
        velocity?.recycle()
        velocity = null
    }

    private fun slideTo(open: Boolean) {
        val changed = open != isOpen
        isOpen = open
        stopAnimations()
        animating = true
        card.animate()
            .translationX(if (open) -revealWidth() else 0f)
            .translationZ(if (open) liftZ else 0f)
            .setDuration(SETTLE_MS).setInterpolator(SETTLE)
            .setUpdateListener { syncTray() }
            .withEndAction { animating = false; syncTray() }
            .start()
        if (changed) listener?.onOpenChanged(open)
    }

    private fun stopAnimations() {
        card.animate().cancel()
        tray.animate().cancel()
        animating = false
    }

    private fun setOffset(x: Float) {
        card.translationX = x
        syncTray()
    }

    /** Past either end the card still follows the finger, at a fraction of the pace and only so far. */
    private fun rubberBand(x: Float): Float {
        val max = revealWidth()
        return when {
            x < -max -> -max - min(overdrag, (-max - x) * OVERDRAG_RESISTANCE)
            x > 0f -> min(overdrag, x * OVERDRAG_RESISTANCE)
            else -> x
        }
    }

    /** How far the card travels to open: from the first visible tile to the tray's end, plus one padding as the gap. */
    private fun revealWidth(): Float {
        for (i in 0 until tray.childCount) {
            val tile = tray.getChildAt(i)
            if (tile.visibility == VISIBLE) return (tray.width - tile.left + tray.paddingStart).toFloat()
        }
        return 0f
    }

    /**
     * Show the tray only once the card is off it (shown at rest, the pill's antialiased corners would
     * fringe the card's), and grow each tile in as it's uncovered.
     */
    private fun syncTray() {
        val uncovered = -card.translationX
        tray.visibility = if (uncovered > 0.5f) VISIBLE else INVISIBLE
        for (i in 0 until tray.childCount) {
            val tile = tray.getChildAt(i)
            if (tile.visibility != VISIBLE || tile.width == 0) continue
            val f = ((uncovered - (tray.width - tile.right)) / tile.width).coerceIn(0f, 1f)
            val scale = TILE_MIN_SCALE + (1f - TILE_MIN_SCALE) * f
            tile.scaleX = scale
            tile.scaleY = scale
            tile.alpha = f
        }
    }

    private companion object {
        /** A release faster than this decides open or shut by its direction alone. */
        const val FLING_DP_PER_S = 350f
        /** How far past either end the card can be pulled. */
        const val OVERDRAG_DP = 24f
        /** Past either end, the card moves this fraction of the finger's travel. */
        const val OVERDRAG_RESISTANCE = 0.3f
        /** The card's lift over the darkened pill while it's dragged or open. */
        const val LIFT_DP = 3f
        /** A tile's size as it starts to be uncovered; it reaches full size once clear of the card. */
        const val TILE_MIN_SCALE = 0.55f
        const val SETTLE_MS = 300L
        const val LIFT_MS = 150L
        const val DISMISS_MS = 250L
        /** Dismiss travel, in row widths: far enough to clear the screen edge from any row inset. */
        const val DISMISS_TRAVEL = 1.25f
        /** Quick off the mark with a long soft landing (the mockup's cubic-bezier(0.2, 0.9, 0.3, 1)). */
        val SETTLE = PathInterpolator(0.2f, 0.9f, 0.3f, 1f)
    }
}
