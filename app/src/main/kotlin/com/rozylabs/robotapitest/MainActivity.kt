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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test bench for [RobotPauseController].
 *
 * Enter the robot's base URL and tap the button: the first tap sends pause and the
 * button becomes a countdown to the automatic resume; further taps restart the
 * countdown. Every request, result, and retry appears in the log pane.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("robot_test", MODE_PRIVATE)

        val logLines = mutableStateListOf<String>()
        var pausedByUs by mutableStateOf(false)
        // The countdown shown on the button. The controller runs its own timer;
        // if the log's resume time disagrees with this countdown, the controller is at fault.
        var resumeDeadlineMs by mutableLongStateOf(0L)

        fun log(line: String) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logLines.add(0, "$ts  $line") // newest first
        }

        var baseUrl by mutableStateOf(prefs.getString("base_url", "http://192.168.3.3:7242")!!)

        val controller = RobotPauseController(
            scope = lifecycleScope,
            target = {
                val url = baseUrl.trim().trimEnd('/')
                if (url.startsWith("http")) RobotTarget("test-robot", url) else null
            },
            persistPausedByUs = { paused ->
                prefs.edit { putBoolean("paused_by_us", paused) }
                pausedByUs = paused
                if (paused) {
                    resumeDeadlineMs =
                        System.currentTimeMillis() + RobotPauseController.DEFAULT_PAUSE_WINDOW_MS
                }
            },
            post = { url ->
                log("POST $url ...")
                val result = httpPost(url)
                log(if (result.confirmed) "-> OK (${result.detail})" else "-> FAILED (${result.detail})")
                result.confirmed
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
                            prefs.edit { putString("base_url", it) }
                        },
                        label = { Text("Robot base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

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
                                resumeDeadlineMs =
                                    System.currentTimeMillis() + RobotPauseController.DEFAULT_PAUSE_WINDOW_MS
                            }
                            controller.onUserTouch()
                        },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    ) {
                        val secondsLeft = ((resumeDeadlineMs - nowMs) / 1000).coerceAtLeast(0)
                        Text(
                            text = if (pausedByUs) "Resume in ${secondsLeft}s (tap to reset)"
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

    /** [confirmed] is what the controller acts on; [detail] is for the log pane only. */
    private data class PostResult(val confirmed: Boolean, val detail: String)

    /**
     * POST with an empty body. The robot answers HTTP 200 even when it refuses the
     * command and reports the real outcome in the response envelope, so the status
     * code alone is not confirmation. Never throws, as the controller requires.
     */
    private suspend fun httpPost(url: String): PostResult = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.outputStream.use { } // empty body
                val code = conn.responseCode
                val body = runCatching {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                if (code !in 200..299) {
                    PostResult(false, "HTTP $code ${body.take(200)}".trim())
                } else {
                    readEnvelope(code, body)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            PostResult(false, e.javaClass.simpleName + e.message?.let { ": $it" }.orEmpty())
        }
    }

    /**
     * `{"status_code":200,"success":true,"message":"","data":{},"error":{...}}` — the
     * `success` field decides, since a refusal also arrives as HTTP 200. A body that is
     * not that envelope leaves the status code as the only signal, so it is trusted and
     * logged verbatim rather than silently read as a refusal.
     */
    private fun readEnvelope(code: Int, body: String): PostResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return PostResult(true, "HTTP $code, no JSON body: ${body.take(200)}")
        if (!json.has("success")) {
            return PostResult(true, "HTTP $code, no success field: ${body.take(200)}")
        }
        if (json.optBoolean("success")) return PostResult(true, "HTTP $code success=true")
        val error = json.optJSONObject("error")
        val reason = listOfNotNull(
            error?.optString("code")?.ifBlank { null },
            error?.optString("message")?.ifBlank { null },
            json.optString("message").ifBlank { null },
        ).joinToString(" ").ifBlank { "no reason given" }
        return PostResult(false, "robot refused: $reason")
    }
}
