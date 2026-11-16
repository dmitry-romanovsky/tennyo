package app.tennyo.mobile.design

import androidx.compose.runtime.Composable
import app.tennyo.mobile.design.generated.resources.Res
import app.tennyo.mobile.design.generated.resources.time_days_ago
import app.tennyo.mobile.design.generated.resources.time_hours_ago
import app.tennyo.mobile.design.generated.resources.time_just_now
import app.tennyo.mobile.design.generated.resources.time_minutes_ago
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** How long ago this epoch-millis moment was, as compact as a list row needs: "just now", "5m ago", "3d ago". */
@Composable
fun Long.relativeTime(now: Long = Clock.System.now().toEpochMilliseconds()): String {
    val minutes = ((now - this).coerceAtLeast(0L) / MILLIS_PER_MINUTE).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.time_just_now)
        minutes < MINUTES_PER_HOUR -> stringResource(Res.string.time_minutes_ago, minutes)
        minutes < MINUTES_PER_DAY -> stringResource(Res.string.time_hours_ago, minutes / MINUTES_PER_HOUR)
        else -> stringResource(Res.string.time_days_ago, minutes / MINUTES_PER_DAY)
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 1_440
