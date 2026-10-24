package pl.f650gs.forum

import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * ScrollView that reports a downward pull started at the very top (pull to refresh).
 * It only observes touches, so nested WebViews and normal scrolling keep working.
 */
class PullRefreshScrollView(context: Context) : ScrollView(context) {
    /** Called with the pull distance in pixels while dragging, and with 0 when released or cancelled. */
    var onPull: ((Float) -> Unit)? = null
    /** Called on release when the pull went past [threshold]. */
    var onRefresh: (() -> Unit)? = null
    var threshold = 0f

    private var startY = 0f
    private var pulling = false
    private var distance = 0f




    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // On a resize ScrollView scrolls the focused view back into sight. With the focus on the scroll view
        // itself (where a screen change puts it) that animates to the bottom, e.g. when the composer hides.
        if (findFocus() === this) return
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.y
                pulling = scrollY == 0
                distance = 0f
            }
            MotionEvent.ACTION_MOVE -> if (pulling) {
                distance = (event.y - startY).coerceAtLeast(0f)
                if (scrollY > 0) pulling = false
                onPull?.invoke(if (pulling) distance else 0f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> pulling = false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val refresh = pulling && distance >= threshold && event.actionMasked == MotionEvent.ACTION_UP
                pulling = false
                onPull?.invoke(0f)
                if (refresh) onRefresh?.invoke()
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
