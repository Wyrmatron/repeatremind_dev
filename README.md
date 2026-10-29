# RepeatRemind

A small, standalone Android reminder app. Native Java and Android framework views;
no runtime libraries, accounts, ads, analytics, network access or background service.
Requires Android 8.0 or newer. Tasks are stored only in the app's private local preferences.
Android cloud backup and device transfer are disabled.

## Use

The app has three rooms, switched with the icon bar at the bottom: **Reminders**, **Missed days** and
**Work tracker**.

- Tap **+** to add a task. An empty list shows only an arrow and the add button below the header.
- Choose every day, selected weekdays, weekly, bi-weekly, monthly, or yearly.
- Weekly repeats from the start date. Strict biweekly repeats every **14 local calendar days**
  from the start date, across month, year and daylight-saving boundaries.
- The biweekly calendar alternative lets you select any combination of the **1st**, **15th**,
  and **last day** of each month. These are independent monthly dates, not 14-day intervals.
- Monthly dates clamp to the last day of short months without changing the requested day
  for later months. February 29 yearly reminders use February 28 outside leap years.
- **Enable reminder** is checked by default; enter notification text and select a local reminder time.
  Unchecking it turns off all reminders for that task.
  Blank notification text defaults to the task name. Tasks can exist without a notification.
- Tap **Completed** in the list or expanded notification. The list button highlights when
  complete; tap it again to undo. Completion resets at local midnight when the next scheduled
  date begins, independently of reminder time. Completing an upcoming occurrence early skips its reminder.
- **Missed days** shows only past scheduled dates that remain incomplete, newest first.
  Completion history is saved locally and catches up after the app has been closed. Today's
  unfinished task is not yet missed. Unselected dates and paused days are excluded. Ignore only
  silences notifications, so ignored days still count if incomplete. Completing an active weekly
  or monthly occurrence late updates that occurrence's saved outcome. Deleting a task deletes its history.
  Tracking starts when this version first opens each existing task; older releases did not
  record historical outcomes, so earlier missed dates cannot be reconstructed.
- Enable **Send additional reminders if not complete** and choose a wait in minutes, hours,
  or days (1 minute to 365 days). Each delivered reminder starts another wait. Regular calendar
  occurrences keep their original schedule; a new occurrence replaces an older unfinished one.
- **Ignore** in the list or expanded notification silences all reminders for that task until
  **Resume** is selected, without marking it complete. The editor also has an Ignore checkbox.
  Swiping away a notification or opening it does not stop follow-ups. Undoing Completed
  resumes follow-ups for an occurrence that has already started, after the configured wait.
- Tap a task to edit, pause, or delete it. **Theme** selects Light, Dark, Colourblind, or Custom.
  Custom has native RGB pickers, hex fields, and a preview for background, button, highlight,
  and text colours. Button and highlight labels automatically use contrasting black or white.
  Selected weekdays remain on one line inside rounded highlights.
- The start date is the earliest allowed occurrence. All dates use the phone's native local time.
- **Temporary task** runs for a set number of days, weeks, months or years counted from the start
  date, then disables itself. The list shows its last day and then **Ended**. Missed days stop at
  the last day. To run it again, lengthen it or move the start date, then re-enable it.
- **Work tracker → Tracker** has three toggles for the selected job that highlight while on:
  **Punch in/out**, **Start/stop productive** and **Start/stop lunch/break**. You switch every toggle
  yourself: productive time and breaks need that job to be punched in, and each is unavailable (shown
  dimmed) while the other runs for that job. Punching out of a job also ends that job's productive or break
  time, which cannot outlast it. Today's worked, productive, unproductive and break time is shown below. Work is saved only when you tap a toggle or edit a day;
  nothing is written on a timer. A shift past midnight stays on the day it started.
- **Work room bar:** just above the room icons, the work room has its own bar: **Tracker**, **Details**,
  **Schedule** and the current job. Tap the job to roll out your jobs, each showing whether it is off,
  punched in, productive or on break, and pick one to point the toggles at it; nothing is punched in or out for you.
  Several jobs can be punched in, and productive, at the same time, each with its own timeline. Day totals
  add each job's own time. **Manage jobs…** (in the same list) adds or renames jobs. A job with no recorded work is deleted; one with
  work is archived, so its history and exports keep its name, and can be restored. Choosing a job writes
  nothing.
- **Work tracker → Details** is a calendar: each worked day has a circle that grows with the hours
  worked. Tap a day for its candle graph, which fills the screen and scrolls: work in the first column,
  productive over unproductive time in the second, and breaks in the third. Each candle shows its start, the
  job's short name (its initials, e.g. United Postal Service → UPS) and its end.
  - Drag the round handles at a candle's start and end to move it in 5-minute steps, or tap a handle to set
    the exact minute with the system time picker.
  - Hold a handle on the touching handle of the next or previous candle of the same kind to merge the two.
  - Swipe sideways across a candle to cut it in two there; cutting work also cuts the time inside it.
  - Tap a candle for its details: job, start and end with dates, length and, for work, the time inside it,
    with buttons to change the times or job, or delete it.
  - **+ Work**, **+ Productive** and **+ Break** add periods.

  Within a job, periods never overlap and productive and break time stay inside their work period; jobs
  that overlap in time get side-by-side lanes in each column. Worked time excludes breaks; unproductive time
  is worked time that was not productive.
- **Export CSV** (in Details) saves a CSV wherever you choose, using Android's own file picker (no storage
  permission). Choose the dates (the month shown, all recorded days, or a range), one job or all, and a format:
  - **Daily summary:** one row per day and job: date, weekday, job, first in, last out, punched, break,
    worked, productive and unproductive hours (decimal), productive %, worked as h:mm, and work periods.
  - **Every period:** one row per work, productive or break period: date, weekday, job, type, start, end,
    minutes and hours.

  The file is UTF-8 with a byte-order mark; job names are quoted when needed and never read as formulas.
  The export screen lists recommended import settings: UTF-8, comma separator, double-quote text
  delimiter, header row, ISO dates (`YYYY-MM-DD`, times `YYYY-MM-DD HH:MM`), and a dot as the decimal mark.
- **Work tracker → Schedule** sets up a before-work reminder. Choose a **regular schedule** (e.g. office) and
  select the days you work, or **shift work** (e.g. retail) and enter start and end times for each day;
  days without times are off. It prompts you when no shifts are entered for the next 7 days.
  Shifts can run past midnight and have an optional break inside the shift. Set **Prep time**,
  **Commute time** and how many minutes before prep to be reminded: the reminder goes off at
  shift start − commute − prep − reminder. It fires once per shift and is skipped once the shift starts.

## Permissions and delivery

The manifest declares exactly three permissions:

| Permission | Purpose |
| --- | --- |
| `POST_NOTIFICATIONS` | Display reminders; requested on Android 13+. |
| `RECEIVE_BOOT_COMPLETED` | Automatically restore reminders after reboot; no permission prompt. |
| `SCHEDULE_EXACT_ALARM` | User-controlled “Alarms & reminders” access on Android 12+, for the selected time. |

After saving the first reminder, enable notification access and precise reminders using the
buttons above the list. If precise access is declined, reminders still use approximate alarms;
Android may delay them. The app never requests network, contacts, location, shared storage,
wake-lock, battery exemption, or foreground-service permissions.

At most two AlarmManager alarms are pending: an exact one for the earliest base or before-work
reminder, and an inexact one for the earliest follow-up. Completed occurrences set no alarm, and
follow-ups set none while notifications are blocked; unblocking them reschedules. The receiver posts
all due notifications and schedules the next alarms, then exits. It does not poll or keep a process
running. Reboot, clock/timezone changes, and app updates reschedule reminders. A task notification
removes itself when the next occurrence begins, and its Completed action still records the day if
tapped after that. Missed-day history is stored separately from task state and written only when
it changes. A late alarm
or reboot produces at most one catch-up notification per task, not a backlog. A skipped local
DST time moves forward by the gap; a repeated hour generates one occurrence, using its first offset.
Completion, Ignore, and pending follow-ups persist across process exits and reboot. Existing saved
tasks migrate automatically with additional reminders switched off. Very short follow-up intervals
can be delayed by Android's idle-mode alarm limits, even with precise reminder access.

Android/OEM restrictions still apply. Force-stopping the app blocks its alarms and boot receiver
until you launch it again. Notification access, channel settings, Do Not Disturb and manufacturer
battery controls can silence or delay delivery. Revoking exact-alarm access cancels existing exact
alarms and stops the app; open it again to resume approximate scheduling, or restore access.
Uninstalling or clearing app data removes all tasks. There is no external synchronization.

## Build

Open in Android Studio, or use the checked-in Gradle wrapper and Android SDK. The repository's
AGP 9.4.1 / Gradle 9.6.0 configuration and JetBrains JDK 21 daemon criteria are preserved;
Gradle can download the configured daemon JDK. App source/bytecode compatibility remains Java 17.

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0'
./gradlew jvmTests lintDebug assembleDebug assembleRelease assembleAndroidTest
```

Set `ANDROID_HOME` or a local untracked `local.properties` containing `sdk.dir=/path/to/sdk`.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk` and can be installed directly.
The size-optimized release APK is `app/build/outputs/apk/release/app-release-unsigned.apk`;
sign it with your own private key before installing or distributing. Never commit signing keys.
The GitHub Actions workflow builds and tests both variants on pushes to main and pull requests.
It does not publish releases or upload APKs, since unsigned builds cannot be installed.
Increase `versionName` and `versionCode` for each release you sign and distribute.
The build downloads tools only on the development machine; the installed app has no network access.

`jvmTests` runs every plain-JDK test in `tests/`. `RecurrenceTest` runs over 21,000 assertions without Android or third-party test libraries,
covering selected days, leap years, short months, start dates, strict 14-day intervals and DST.
`ReminderStateTest` checks completion, Ignore/Resume, interval validation, missed alarms,
stale notification actions, local midnight/DST rollover, saved completion and missed history,
clock changes, and unchanged recurrence dates for every schedule. `ColourMathTest` checks hex input
and readable label contrast over a grid of sRGB colours. `ScheduleTest` checks schedule copying,
validation, list labels, and the date-level recurrence helpers. `WorkdaysTest` checks shift and
break validation, overnight shifts, reminder lead times across midnight and DST, per-day shifts,
one delivery per shift, and workday labels. `WorkLogTest` checks the punch clock toggles, totals,
editing limits and 5-minute snapping, adding, deleting, merging and cutting periods, short job names, night shifts, jobs (switching, totals
by job, archiving, storage), and both CSV formats including quoting. `ReminderStateTest` also covers temporary tasks,
wake-up selection, late completion, clock changes and compact history storage.
The framework-only `SmokeInstrumentation` additionally exercises editor persistence, list
buttons, weekday highlights, missed-history filtering, the work tracker toggles, calendar and day view,
custom themes, actual notification actions,
permissions, and OS alarm delivery on a disposable device.
CI runs these device tests on an Android 15 emulator.

The calendar/hourglass launcher icon is a small native vector with an adaptive-icon wrapper.
