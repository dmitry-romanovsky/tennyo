package app.tennyo.host.engine.platform

import kotlin.time.Clock

/** Wall-clock time for deadlines and timestamps; callers inject their own clock where tests need to move it. */
fun hostWallClockMillis(): Long = Clock.System.now().toEpochMilliseconds()
