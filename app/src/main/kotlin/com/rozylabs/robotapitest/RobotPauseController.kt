package com.rozylabs.robotapitest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** The paired robot as the controller needs it: id for logs, base URL to call. */
data class RobotTarget(val robotId: String, val baseUrl: String)

/**
 * Halts the paired Saha Robotik cleaning robot while a visitor is using the kiosk.
 *
 * This is a 1:1 copy of the production class. The ONLY change for this test app:
 * Timber logging is replaced by an injected [log] lambda so log lines can be shown
 * on screen. The pause/resume logic is untouched.
 *
 * Semantics (spec 2026-07-07):
 *  - Any screen touch on a paired kiosk pauses the robot (`POST <base>/api/v1/tasks/pause`).
 *    Pause is sent ONCE per engagement — repeated touches never re-POST.
 *  - Resume (`POST <base>/api/v1/tasks/resume`) fires 60 s after the LAST touch: a
 *    sliding window, restarted on every touch.
 *  - The robot must NEVER be stranded paused: resume retries with backoff
 *    (5 s → 15 s → 30 s, then every 60 s indefinitely), a `pausedByUs` flag is persisted
 *    so a crashed/killed app resumes the robot on next boot, and the resume target is the
 *    address captured AT PAUSE TIME (a mid-pause re-pair still resumes the old robot).
 *  - Unpaired / address-unresolved kiosks are a total no-op.
 *
 * Touch events are funneled through a [MutableSharedFlow] collected on [scope]'s
 * dispatcher, so [onUserTouch] is safe (and cheap) to call from the UI thread on every
 * finger-down. All dependencies are injected as lambdas for testability.
 *
 * @param target current pairing, or null when unpaired/unresolved (re-read on every touch).
 * @param persistPausedByUs persists the crash-recovery flag.
 * @param post POSTs to the given URL, true on 2xx; must not throw.
 * @param log receives every log line the production class would send to Timber.
 */
class RobotPauseController(
    private val scope: CoroutineScope,
    private val target: () -> RobotTarget?,
    private val persistPausedByUs: (Boolean) -> Unit,
    private val post: suspend (url: String) -> Boolean,
    private val pauseWindowMs: Long = DEFAULT_PAUSE_WINDOW_MS,
    private val log: (String) -> Unit = {},
) {
    private enum class State { IDLE, PAUSING, PAUSED }

    private var state = State.IDLE
    private var pausedTarget: RobotTarget? = null
    private var resumeJob: Job? = null

    // Buffer(1)+DROP_OLDEST: a touch storm collapses to "touched again" — exactly the
    // signal the sliding window needs — while the collector serializes state changes.
    private val touches = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch { touches.collect { handleTouch() } }
    }

    /** UI-thread entry point — called on every finger-down anywhere on screen. */
    fun onUserTouch() {
        touches.tryEmit(Unit)
    }

    /**
     * Boot-time crash recovery: if a previous process paused the robot and died before
     * resuming, resume it now (using the current resolved address). Call once at startup.
     */
    fun resumeIfPreviouslyPausedOnBoot(wasPausedByUs: Boolean) {
        if (!wasPausedByUs) return
        val t = target()
        if (t == null) {
            // Pairing/address gone since the crash — nothing we can call; drop the flag
            // so we don't retry forever against nothing.
            log("paused flag set but no robot target on boot — clearing")
            persistPausedByUs(false)
            return
        }
        log("previous run left ${t.robotId} paused — resuming on boot")
        state = State.PAUSED
        pausedTarget = t
        resumeJob?.cancel()
        resumeJob = scope.launch { resumeUntilSuccess(t) }
    }

    private var loggedNoTarget = false

    private suspend fun handleTouch() {
        when (state) {
            State.PAUSING -> Unit // pause in flight; the queued touch after it extends the window
            State.PAUSED -> restartResumeWindow()
            State.IDLE -> {
                val t = target()
                if (t == null) {
                    // Unpaired or address unresolved → total no-op. Logged once per process
                    // so a field kiosk that SHOULD be pausing a robot is diagnosable.
                    if (!loggedNoTarget) {
                        loggedNoTarget = true
                        log("touch ignored — no paired robot / unresolved address")
                    }
                    return
                }
                loggedNoTarget = false
                state = State.PAUSING
                if (postPauseWithRetries(t)) {
                    state = State.PAUSED
                    pausedTarget = t
                    persistPausedByUs(true)
                    log("paused ${t.robotId}")
                    restartResumeWindow()
                } else {
                    // Robot unreachable — it never paused, so there is nothing to resume.
                    // The next touch tries again.
                    log("pause failed for ${t.robotId} — giving up until next touch")
                    state = State.IDLE
                }
            }
        }
    }

    /** Sliding 60 s window: each touch cancels the pending resume and starts it anew. */
    private fun restartResumeWindow() {
        val t = pausedTarget ?: return
        resumeJob?.cancel()
        resumeJob = scope.launch {
            delay(pauseWindowMs)
            resumeUntilSuccess(t)
        }
    }

    private suspend fun postPauseWithRetries(t: RobotTarget): Boolean {
        for (backoffMs in PAUSE_RETRY_DELAYS_MS) {
            delay(backoffMs)
            if (post("${t.baseUrl}$PAUSE_PATH")) return true
        }
        return false
    }

    /**
     * Resume with escalating backoff, then every [RESUME_STEADY_RETRY_MS] FOREVER while
     * paused — a stranded paused robot is the one unacceptable failure mode. Cancelled
     * (and rescheduled) by any new touch; the robot is still paused then, so no re-pause.
     */
    private suspend fun resumeUntilSuccess(t: RobotTarget) {
        var attempt = 0
        while (true) {
            if (post("${t.baseUrl}$RESUME_PATH")) {
                state = State.IDLE
                pausedTarget = null
                persistPausedByUs(false)
                log("resumed ${t.robotId}")
                return
            }
            val backoff = RESUME_RETRY_DELAYS_MS.getOrElse(attempt) { RESUME_STEADY_RETRY_MS }
            log("resume failed for ${t.robotId} — retrying in ${backoff}ms")
            delay(backoff)
            attempt++
        }
    }

    companion object {
        /** Resume this long after the LAST touch (spec: 60 s sliding window). */
        const val DEFAULT_PAUSE_WINDOW_MS = 60_000L
        const val PAUSE_PATH = "/api/v1/tasks/pause"
        const val RESUME_PATH = "/api/v1/tasks/resume"
        private val PAUSE_RETRY_DELAYS_MS = listOf(0L, 2_000L, 5_000L)
        private val RESUME_RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L)
        private const val RESUME_STEADY_RETRY_MS = 60_000L
    }
}
