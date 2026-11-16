package app.tennyo.mobile.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.launch

/**
 * Pans content that scrolls on both axes at once: a single drag moves the vertical and the
 * horizontal state together, which the built-in scroll modifiers cannot do because each of them
 * claims the drag for its own axis.
 */
@Composable
fun Modifier.panScroll(
    vertical: ScrollState,
    horizontal: ScrollState,
    enabled: Boolean = true,
): Modifier = this
    .verticalScroll(vertical, enabled = false)
    .horizontalScroll(horizontal, enabled = false)
    .panGestures(vertical, horizontal, enabled)

@Composable
private fun Modifier.panGestures(
    vertical: ScrollableState,
    horizontal: ScrollableState,
    enabled: Boolean,
): Modifier {
    val scope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }
    val flingBehavior = ScrollableDefaults.flingBehavior()
    return pointerInput(vertical, horizontal, enabled) {
        if (!enabled) return@pointerInput
        detectDragGestures(
            onDragStart = { velocityTracker.resetTracking() },
            onDragCancel = { velocityTracker.resetTracking() },
            onDragEnd = {
                val velocity = velocityTracker.calculateVelocity()
                scope.launch { vertical.flingBy(flingBehavior, -velocity.y) }
                scope.launch { horizontal.flingBy(flingBehavior, -velocity.x) }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                // Raw deltas apply in gesture order; scrolling through `scroll` would queue each
                // event on the scroll mutex and drop the ones that lose the race.
                vertical.dispatchRawDelta(-dragAmount.y)
                horizontal.dispatchRawDelta(-dragAmount.x)
                velocityTracker.addPointerInputChange(change)
            },
        )
    }
}

private suspend fun ScrollableState.flingBy(flingBehavior: FlingBehavior, velocity: Float) {
    if (velocity == 0f) return
    scroll { with(flingBehavior) { performFling(velocity) } }
}

