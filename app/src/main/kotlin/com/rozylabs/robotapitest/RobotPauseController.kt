package com.rozylabs.robotapitest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** The robot to control: [robotId] for log lines, [baseUrl] like "http://192.168.1.42:7242". */
data class RobotTarget(val robotId: String, val baseUrl: String)

/**
 * Pauses the cleaning robot while a person is using the kiosk touchscreen.
 *
 * Behavior:
 *  - The first touch of an engagement sends `POST /api/v1/tasks/pause` once
 *    (attempts at 0 / 2 / 5 s). Repeated touches never re-send pause.
 *  - Every touch restarts a 60 s window. When the window expires with no further
 *    touches, `POST /api/v1/tasks/resume` is sent.
 *  - Resume must always land eventually: it retries at 5 / 15 / 30 s, then every
 *    60 s until the robot answers 2xx. A persisted flag lets an app that was killed
 *    while the robot was paused send resume on its next launch.
 *  - When no robot is configured, touches are ignored.
 *
 * All I/O is injected: [post] performs an HTTP POST and returns true on any 2xx
 * (it must not throw), [target] supplies the current robot, [persistPausedByUs]
 * stores the crash-recovery flag, [log] receives progress lines.
 *
 * [onUserTouch] is safe to call from the UI thread on every finger-down; events are
 * handled one at a time on [scope]'s dispatcher.
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

    // 1-slot buffer, drop oldest: a burst of touches collapses to a single
    // "touched again" signal while the collector serializes state changes.
    private val touches = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch { touches.collect { handleTouch() } }
    }

    /** Call on every finger-down anywhere on screen. */
    fun onUserTouch() {
        touches.tryEmit(Unit)
    }

    /**
     * Crash recovery: if a previous run paused the robot and died before resuming,
     * resume it now. Call once at startup with the persisted flag.
     */
    fun resumeIfPreviouslyPausedOnBoot(wasPausedByUs: Boolean) {
        if (!wasPausedByUs) return
        val t = target()
        if (t == null) {
            // No robot configured anymore; clear the flag so we don't retry forever.
            log("paused flag set but no robot target on boot, clearing")
            persistPausedByUs(false)
            return
        }
        log("previous run left ${t.robotId} paused, resuming on boot")
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
                    if (!loggedNoTarget) {
                        loggedNoTarget = true
                        log("touch ignored, no robot configured")
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
                    // Robot unreachable: it never paused, so there is nothing to resume.
                    log("pause failed for ${t.robotId}, giving up until next touch")
                    state = State.IDLE
                }
            }
        }
    }

    /** Each touch cancels the pending resume and restarts the window. */
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

    /** Retries until the robot confirms resume; cancelled and rescheduled by any new touch. */
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
            log("resume failed for ${t.robotId}, retrying in ${backoff}ms")
            delay(backoff)
            attempt++
        }
    }

    companion object {
        /** Resume this long after the last touch. */
        const val DEFAULT_PAUSE_WINDOW_MS = 60_000L
        const val PAUSE_PATH = "/api/v1/tasks/pause"
        const val RESUME_PATH = "/api/v1/tasks/resume"
        private val PAUSE_RETRY_DELAYS_MS = listOf(0L, 2_000L, 5_000L)
        private val RESUME_RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L)
        private const val RESUME_STEADY_RETRY_MS = 60_000L
    }
}
