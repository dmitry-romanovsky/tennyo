package app.tennyo.mobile.design.theme

/**
 * Standard animation durations. Prefer these over hardcoded literals or a
 * bare `tween()` (which silently relies on Compose's implicit 300ms
 * default) so animation pacing stays consistent and easy to audit.
 *
 * `Animatable` vs `animate*AsState`:
 * - Prefer `animate*AsState` (e.g. `animateFloatAsState`, `animateDpAsState`)
 *   whenever the animated value is a simple function of composition state
 *   (a boolean flag, an enum, a target derived from input) — it's
 *   declarative, cancels/restarts correctly on recomposition, and needs no
 *   manual coroutine management.
 * - Reach for `Animatable` only when you need imperative control the
 *   state-driven APIs can't give you: sequencing multiple animations,
 *   reacting to gesture velocity (e.g. a fling), interrupting a running
 *   animation from an event callback, or animating a value that isn't
 *   simply derived from composition state.
 */
object AnimationDurations {
    const val quickMillis = 150
    const val regularMillis = 300
    const val moderateMillis = 500
}
