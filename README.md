# Robot API Test

A tiny one-screen Android app to verify the kiosk's **pause-the-robot-on-touch** logic
against a real Saha Robotik robot, without needing our main app.

## What it does

- `RobotPauseController.kt` is a 1:1 copy of the production class (only change:
  Timber logging → an injected `log` lambda so lines show on screen). This is the
  code under test.
- The screen has a robot base-URL field and one big button:
  - **First tap** → `POST <base>/api/v1/tasks/pause` (retries at 0s / 2s / 5s).
  - Button turns into a **countdown**: resume fires 60 s after the *last* tap.
  - **Every further tap** restarts the 60 s window (no re-POST of pause).
  - When the window expires → `POST <base>/api/v1/tasks/resume` (retries
    5s → 15s → 30s, then every 60s forever until it succeeds).
- The log pane shows every POST, result, and retry with timestamps — no adb needed.
- Crash recovery: if the app is killed while the robot is paused, it resumes the
  robot on next launch (`paused_by_us` flag in SharedPreferences).

## Run it

1. Open this folder in Android Studio, let Gradle sync, run on a tablet/phone
   that is on the **same network as the robot**.
2. Enter the robot's base URL (e.g. `http://192.168.1.42:7242` — the robot API listens on port 7242, plain http, no auth).
3. Tap the button and watch the robot + the log.

Notes:
- The robot API is plain http, so the manifest sets `usesCleartextTraffic="true"`.
- The countdown shown on the button is UI-side; the controller runs its own timer.
  If the log's resume time disagrees with the countdown, that's a controller bug —
  which is exactly what this app is for.
- To test faster than 60 s, pass a smaller `pauseWindowMs` where the controller is
  constructed in `MainActivity.kt`.

Architecture, diagrams, and the review notes: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
