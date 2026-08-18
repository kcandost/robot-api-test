package com.rozylabs.robotapitest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.lifecycleScope

/**
 * One-screen harness for [RobotPauseController].
 *
 * Enter the robot's base URL, tap the big button. The first tap POSTs pause; the button
 * turns into a countdown to the automatic resume. Every further tap restarts the countdown
 * (the sliding window). The log pane shows exactly what the controller did, including
 * retries, so failures are visible without adb.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("robot_test", MODE_PRIVATE)

        // Screen state the controller drives via its injected lambdas.
        val logLines = mutableStateListOf<String>()
        var pausedByUs by mutableStateOf(false)
        // When the current resume window ends. The UI owns this; the controller owns the
        // actual resume timing — the two only drift if the controller is buggy, which is
        // exactly what this app exists to reveal (compare countdown vs. log lines).
        var resumeDeadlineMs by mutableLongStateOf(0L)

        fun log(line: String) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logLines.add(0, "$ts  $line") // newest first
        }

        var baseUrl by mutableStateOf(prefs.getString("base_url", "http://192.168.1.100:7242")!!)

        val controller = RobotPauseController(
            scope = lifecycleScope,
            target = {
                val url = baseUrl.trim().trimEnd('/')
                if (url.startsWith("http")) RobotTarget("test-robot", url) else null
            },
            persistPausedByUs = { paused ->
                prefs.edit().putBoolean("paused_by_us", paused).apply()
                pausedByUs = paused
                if (paused) resumeDeadlineMs = System.currentTimeMillis() + RobotPauseController.DEFAULT_PAUSE_WINDOW_MS
            },
            post = { url ->
                log("POST $url …")
                val ok = httpPost(url)
                log(if (ok) "→ OK" else "→ FAILED")
                ok
            },
            log = ::log,
        )
        controller.resumeIfPreviouslyPausedOnBoot(prefs.getBoolean("paused_by_us", false))

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            prefs.edit().putString("base_url", it).apply()
                        },
                        label = { Text("Robot base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Ticks every 200 ms while paused so the countdown text stays live.
                    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(pausedByUs) {
                        while (pausedByUs) {
                            nowMs = System.currentTimeMillis()
                            delay(200)
                        }
                    }

                    Button(
                        onClick = {
                            if (pausedByUs) {
                                resumeDeadlineMs = System.currentTimeMillis() + RobotPauseController.DEFAULT_PAUSE_WINDOW_MS
                            }
                            controller.onUserTouch()
                        },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    ) {
                        val secondsLeft = ((resumeDeadlineMs - nowMs) / 1000).coerceAtLeast(0)
                        Text(
                            text = if (pausedByUs) "Resume in ${secondsLeft}s — tap to reset"
                            else "TAP TO PAUSE ROBOT",
                            fontSize = 22.sp,
                        )
                    }

                    Text("Log (newest first)", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(logLines) { line ->
                            Text(line, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
        }
    }

    /** Bare POST with empty body; true on any 2xx. Never throws (the controller requires that). */
    private suspend fun httpPost(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                conn.doOutput = true
                conn.outputStream.use { } // empty body
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
