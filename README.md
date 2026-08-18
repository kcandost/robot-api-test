# Robot API Test

This is a small harness I built around `RobotPauseController.kt`, the class that pauses
the cleaning robot while someone is using our kiosk. I can't share the app it normally
lives in, so I extracted the class unchanged (the only edit: Timber logging became an
on-screen log) and wrapped it in a one-screen app so the logic can be tested and
corrected against a real robot. Whatever gets fixed here, I'll port back.

## What I tried to build

- First touch sends `POST <base>/api/v1/tasks/pause`, once per engagement, with
  attempts at 0 / 2 / 5 s. The button then turns into a 60 s countdown.
- Every further tap restarts the countdown without re-sending pause.
- When 60 s pass with no taps, it sends `POST <base>/api/v1/tasks/resume`, retrying
  at 5 / 15 / 30 s and then every 60 s until the robot answers 2xx. My intent was that
  the robot can never be left stranded in pause.
- If the app dies while the robot is paused, a persisted flag makes the next launch
  send resume immediately.

Every request, result, and retry shows in the on-screen log with timestamps, so
adb shouldn't be needed.

## Running it

Open this folder in Android Studio and run it on a device on the same network as the
robot. The URL field defaults to port 7242, plain http, which is what our pairing code
uses; the manifest allows cleartext traffic for that reason. The 60 s window is the
`pauseWindowMs` parameter where the controller is constructed in `MainActivity.kt`,
in case waiting a full minute per cycle gets tedious.

## What I'd like checked

Whether the calls, timing, and retry behavior above actually hold against a real robot,
and whether my assumptions about the robot API (paths, empty body, 2xx-only success,
no auth) are right. The places where I'm least confident are listed in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), together with the state machine and a
table of failure symptoms mapped to my best guesses at their causes.

One caveat about the UI: the countdown on the button is my own UI-side clock. The
controller runs its own timer internally. If the log's resume moment disagrees with
the countdown, that disagreement is a controller bug, and surfacing exactly that kind
of thing is why I built this app.
