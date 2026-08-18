# Robot API Test

Harness around `RobotPauseController.kt`, copied out of our app unchanged (only edit:
Timber became the on-screen log). Whatever gets fixed here, I'll port back.

## What I tried to build

- First touch sends `POST <base>/api/v1/tasks/pause`, once per engagement, attempts
  at 0 / 2 / 5 s. The button becomes a 60 s countdown.
- Further taps restart the countdown without re-sending pause.
- 60 s with no taps sends `POST <base>/api/v1/tasks/resume`, retrying at 5 / 15 / 30 s,
  then every 60 s until 2xx. Intent: the robot can never be left stranded in pause.
- If the app dies while the robot is paused, a persisted flag makes the next launch
  send resume immediately.

Every request, result, and retry shows in the on-screen log with timestamps.

## Running it

Android Studio, on a device on the robot's network. The URL field defaults to
port 7242, plain http (manifest allows cleartext for this). `pauseWindowMs`, where the
controller is constructed in `MainActivity.kt`, shortens the 60 s wait for testing.

## What I'd like checked

Whether the behavior above holds against a real robot, and whether my API assumptions
(paths, empty body, 2xx-only success, no auth) are right. The parts I'm least sure of
are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), with the state machine and my
symptom-to-cause guesses. One caveat: the button countdown is a UI-side clock; if it
disagrees with the log's resume moment, the controller's own timer is at fault.
