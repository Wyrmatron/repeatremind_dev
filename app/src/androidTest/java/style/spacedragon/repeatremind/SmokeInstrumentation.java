package style.spacedragon.repeatremind;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Framework-only device smoke tests. Run on a disposable emulator, not a personal phone. */
public final class SmokeInstrumentation extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Bundle status = new Bundle();
        status.putString("class", getClass().getName()); status.putString("test", "appSmoke");
        status.putInt("numtests", 1); status.putInt("current", 1); sendStatus(1, status);
        Context context = getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("repeatremind", Context.MODE_PRIVATE);
        String originalTasks = preferences.getString("tasks", "[]");
        int originalTheme = preferences.getInt("theme", 0);
        int originalNext = preferences.getInt("next_id", 1);
        int[] originalCustom = new TaskStore(context).customColours();
        SharedPreferences workLog = context.getSharedPreferences("repeatremind_worklog", Context.MODE_PRIVATE);
        java.util.Map<String, ?> originalWorkLog = new java.util.HashMap<>(workLog.getAll());
        int resultCode = Activity.RESULT_CANCELED;
        try {
            Set<String> permissions = new HashSet<>(Arrays.asList(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions));
            check(permissions.equals(Set.of("android.permission.POST_NOTIFICATIONS",
                    "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SCHEDULE_EXACT_ALARM")),
                    "Exactly the three intended permissions");
            shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
            shell("appops set " + context.getPackageName() + " SCHEDULE_EXACT_ALARM allow");
            await(() -> Reminders.allowed(context), "Notification access granted");
            await(() -> Reminders.precise(context), "Exact alarm access granted");

            TaskStore store = new TaskStore(context);
            store.save(new ArrayList<>()); store.theme(0);
            Activity main = startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            check(findText(main, "+  Add repeating task") != null, "Empty add button");
            screenshot("smoke-empty.png");
            ActivityMonitor monitor = addMonitor(TaskEditorActivity.class.getName(), null, false);
            ui(() -> findText(main, "+  Add repeating task").performClick());
            Activity editor = waitForMonitorWithTimeout(monitor, 15000); removeMonitor(monitor);
            check(editor != null, "Editor opened"); waitForIdleSync();
            ui(() -> {
                EditText title = (EditText) flatten(editor).stream().filter(v -> v instanceof EditText
                        && "Task name".equals(String.valueOf(((EditText) v).getHint()))).findFirst().orElseThrow();
                title.setText("Monthly smoke test");
                Spinner repeat = flatten(editor).stream().filter(v -> v instanceof Spinner)
                        .map(v -> (Spinner) v).filter(v -> v.getAdapter().getCount() == 6).findFirst().orElseThrow();
                check(repeat.getAdapter().getCount() == 6, "All schedule options available");
                repeat.setSelection(1);
            });
            waitForIdleSync();
            await(() -> findText(editor, "MON") != null && findText(editor, "MON").getHeight() > 0, "Weekday layout ready");
            int[] weekdayBounds = new int[2];
            ui(() -> {
                Button monday = (Button) findText(editor, "MON");
                weekdayBounds[0] = monday.getHeight(); weekdayBounds[1] = monday.getTop();
                monday.performClick();
            });
            await(() -> findText(editor, "MON").getLineCount() == 1, "Weekday layout after toggle");
            ui(() -> findText(editor, "MON").performClick());
            await(() -> findText(editor, "MON").getLineCount() == 1, "Highlighted weekday layout ready");
            ui(() -> {
                Button monday = (Button) findText(editor, "MON");
                check("MON".contentEquals(monday.getText()) && monday.getLineCount() == 1,
                        "Selected weekday stays on a single line");
                check(monday.isSelected() && monday.getHeight() == weekdayBounds[0] && monday.getTop() == weekdayBounds[1],
                        "Selection highlights without moving or clipping the weekday");
                Spinner repeat = flatten(editor).stream().filter(v -> v instanceof Spinner)
                        .map(v -> (Spinner) v).filter(v -> v.getAdapter().getCount() == 6).findFirst().orElseThrow();
                repeat.setSelection(4);
            });
            waitForIdleSync();
            ui(() -> {
                flatten(editor).stream().filter(v -> v instanceof NumberPicker)
                        .map(v -> (NumberPicker) v).findFirst().orElseThrow().setValue(31);
                CheckBox reminder = (CheckBox) findText(editor, "Enable reminder");
                check(reminder.isChecked(), "Reminder enabled by default");
                reminder.setChecked(false); reminder.setChecked(true);
                EditText notification = (EditText) flatten(editor).stream().filter(v -> v instanceof EditText
                        && "Defaults to task name".equals(String.valueOf(((EditText) v).getHint()))).findFirst().orElseThrow();
                notification.setText("Custom notification text");
                ((Switch) findText(editor, "Send additional reminders if not complete")).setChecked(true);
                EditText interval = (EditText) flatten(editor).stream().filter(v -> v instanceof EditText
                        && "Interval".equals(String.valueOf(((EditText) v).getHint()))).findFirst().orElseThrow();
                interval.setText("2");
            });
            waitForIdleSync(); screenshot("smoke-editor.png");
            ui(() -> {
                findText(editor, "Save task").performClick();
            });
            waitForIdleSync();
            List<Task> tasks = store.load();
            check(tasks.size() == 1, "Task saved");
            Task task = tasks.get(0);
            check(task.schedule.kind == Schedule.Kind.MONTHLY && task.schedule.day == 31, "Monthly day persisted");
            check(task.reminder.equals("Custom notification text"), "Custom notification text persisted");
            check(task.nextDue > System.currentTimeMillis(), "Future alarm persisted");
            check(task.followUpEnabled && task.followUpMinutes == 120, "Follow-up interval persisted in minutes");
            await(() -> findText(main, "+") != null, "Main activity resumed with compact plus button after save");
            await(() -> findText(main, "Completed") != null, "Task row layout ready");
            check(findText(main, "Completed") instanceof Button, "Native Completed button exists");
            ui(() -> findText(main, "Completed").performClick()); waitForIdleSync();
            await(() -> store.load().get(0).completed && findText(main, "Completed") != null
                    && findText(main, "Completed").isSelected(), "Completed button saves and highlights");
            ui(() -> findText(main, "Completed").performClick()); waitForIdleSync();
            check(!store.load().get(0).completed, "Completed button can undo completion");

            JSONObject legacy = task.toJson();
            for (String key : new String[]{"followUpEnabled", "followUpMinutes", "completed", "ignored",
                    "followUpAt", "occurrenceDate", "occurrenceId", "occurrenceStarted", "history", "historyThrough"}) legacy.remove(key);
            Task migrated = Task.fromJson(legacy);
            check(!migrated.completed && !migrated.ignored && !migrated.followUpEnabled
                    && migrated.followUpMinutes == 60 && migrated.title.equals(task.title), "Old tasks migrate safely");

            // Both tasks due at once must post in a single wake-up, even with the activity closed.
            ui(main::finish); waitForIdleSync();
            task.schedule.kind = Schedule.Kind.EVERY_DAY;
            task.occurrenceDate = LocalDate.now();
            task.nextDue = System.currentTimeMillis() - 1000;
            Task second = Task.fromJson(task.toJson()); second.id = store.nextId(tasks);
            second.title = "Second simultaneous task";
            tasks.add(second); store.save(tasks);
            new ReminderReceiver().onReceive(context, new Intent());
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            await(() -> Arrays.stream(notifications.getActiveNotifications())
                    .filter(n -> n.getId() == task.id || n.getId() == second.id).count() == 2,
                    "Both simultaneous reminders delivered");
            check(Arrays.stream(notifications.getActiveNotifications()).anyMatch(n ->
                    "Custom notification text".equals(String.valueOf(n.getNotification().extras.getCharSequence("android.text")))),
                    "Notification contains custom text");
            tasks = store.load();
            check(tasks.stream().allMatch(t -> t.nextDue > System.currentTimeMillis()), "Late alarms advance");
            long next = tasks.get(0).nextDue;
            Reminders.reconcile(context, false);
            check(store.load().get(0).nextDue == next, "Repeated reconciliation does not skip a future occurrence");
            Notification first = Arrays.stream(notifications.getActiveNotifications()).filter(n -> n.getId() == task.id)
                    .findFirst().orElseThrow().getNotification();
            check(first.actions.length == 2 && "Completed".contentEquals(first.actions[0].title)
                    && "Ignore".contentEquals(first.actions[1].title), "Expanded notification has both actions");
            first.actions[0].actionIntent.send();
            await(() -> store.load().get(0).completed, "Notification Completed action persisted");
            check(store.load().get(0).followUpAt == 0, "Notification completion cancels follow-ups");
            Task persisted = store.load().get(0);
            check(Boolean.TRUE.equals(persisted.history.get(persisted.occurrenceDate)), "Notification completion history is stored");
            await(() -> Arrays.stream(notifications.getActiveNotifications()).noneMatch(n -> n.getId() == task.id),
                    "Completion dismisses notification");
            Notification other = Arrays.stream(notifications.getActiveNotifications()).filter(n -> n.getId() == second.id)
                    .findFirst().orElseThrow().getNotification();
            other.actions[1].actionIntent.send();
            await(() -> store.load().get(1).ignored, "Notification Ignore action persisted");
            check(!store.load().get(1).completed && store.load().get(1).nextAlarm() == Long.MAX_VALUE,
                    "Ignore silences without completion");

            // Exercise the real OS delivery path, with no activity running.
            notifications.cancelAll();
            await(() -> Arrays.stream(notifications.getActiveNotifications())
                    .noneMatch(n -> n.getId() == task.id || n.getId() == second.id), "Task notifications cleared before OS delivery test");
            tasks = store.load(); tasks.remove(1);
            Task fresh = tasks.get(0);
            fresh.nextDue = System.currentTimeMillis() + 3000;
            fresh.occurrenceDate = null; fresh.occurrenceStarted = false; fresh.completed = false; fresh.followUpAt = 0;
            fresh.history.clear();
            store.save(tasks);
            Reminders.reconcile(context, false);
            long timeout = System.currentTimeMillis() + 20000;
            while (Arrays.stream(notifications.getActiveNotifications()).noneMatch(n -> n.getId() == task.id)
                    && System.currentTimeMillis() < timeout) {
                Thread.sleep(250);
            }
            check(Arrays.stream(notifications.getActiveNotifications()).anyMatch(n -> n.getId() == task.id),
                    "AlarmManager delivered after activity closed");
            first.actions[0].actionIntent.send(); waitForIdleSync();
            check(!store.load().get(0).completed, "Old notification cannot complete a new occurrence");
            Notification current = Arrays.stream(notifications.getActiveNotifications()).filter(n -> n.getId() == task.id)
                    .findFirst().orElseThrow().getNotification();
            current.actions[1].actionIntent.send();
            await(() -> store.load().get(0).ignored, "Current notification Ignore works");
            notifications.cancelAll();
            for (int theme = 0; theme < 3; theme++) {
                store.theme(theme);
                Activity themed = startActivitySync(new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
                await(() -> findText(themed, "Monthly smoke test") != null, "Theme " + theme + " renders tasks");
                check(findText(themed, "Completed") instanceof Button, "Theme " + theme + " has completion control");
                if (theme == 0) {
                    ui(() -> findText(themed, "Resume").performClick()); waitForIdleSync();
                    check(!store.load().get(0).ignored && store.load().get(0).followUpAt > 0, "Resume restores follow-ups");
                }
                screenshot("smoke-theme-" + theme + ".png");
                ui(themed::finish); waitForIdleSync();
            }

            // Exercise rollover and history through the real activity/store, without changing the device clock.
            LocalDate today = LocalDate.now();
            Task rollover = new Task(); rollover.id = store.nextId(store.load()); rollover.title = "Daily rollover test";
            rollover.schedule.start = today.minusDays(2); rollover.hasReminder = false;
            rollover.occurrenceDate = today.minusDays(1); rollover.occurrenceId = 7; rollover.completed = true;
            rollover.historyThrough = today.minusDays(1);
            rollover.nextDue = today.atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            Task missed = Task.fromJson(rollover.toJson()); missed.id = store.nextId(List.of(rollover));
            missed.title = "Missed history test"; missed.completed = false;
            store.save(new ArrayList<>(List.of(rollover, missed)));
            Activity history = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            check(store.load().stream().noneMatch(t -> t.completed), "Activity rolls yesterday's completion over before reminder time");
            ui(() -> findText(history, "Missed days").performClick()); waitForIdleSync();
            await(() -> findText(history, "Missed history test") != null, "Missed tab includes incomplete past date");
            check(findText(history, "Daily rollover test") == null && findText(history, "Completed") == null,
                    "Missed tab excludes completed days and current task controls");
            screenshot("smoke-missed.png"); ui(history::finish); waitForIdleSync();

            // Work tracker: toggles, calendar, day view and schedule, through the real activity and store.
            workLog.edit().clear().commit();
            Activity tracker = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            ui(() -> findText(tracker, "Work tracker").performClick()); waitForIdleSync();
            await(() -> findText(tracker, "Punch in") != null && findText(tracker, "Main job \u25BE") != null
                    && findText(tracker, "Tracker").isSelected(), "Work tracker room shows the punch clock and its bar with the job");
            check(!findText(tracker, "Start productive").isEnabled() && !findText(tracker, "Start lunch/break").isEnabled(),
                    "Productive and break wait for punch in");
            ui(() -> findText(tracker, "Punch in").performClick()); waitForIdleSync();
            await(() -> findText(tracker, "Punch out") != null && findText(tracker, "Start productive").isEnabled(),
                    "Punch in enables productive time");
            ui(() -> findText(tracker, "Start productive").performClick()); waitForIdleSync();
            await(() -> findText(tracker, "Stop productive") != null, "Productive time starts");
            check(findText(tracker, "Stop productive").isSelected() && findText(tracker, "Punch out").isSelected(),
                    "Active toggles are highlighted");
            check(!findText(tracker, "Start lunch/break").isEnabled(), "A break is unavailable while productive, not switched for you");
            check(new WorkLogStore(context).load().running(WorkLog.Kind.PRODUCTIVE, WorkLog.FIRST_JOB), "Productive time is stored");
            ui(() -> findText(tracker, "Stop productive").performClick()); waitForIdleSync();
            await(() -> findText(tracker, "Start lunch/break") != null && findText(tracker, "Start lunch/break").isEnabled(),
                    "Stopping productive time allows a break");
            ui(() -> findText(tracker, "Start lunch/break").performClick()); waitForIdleSync();
            await(() -> findText(tracker, "Stop lunch/break") != null, "A break starts");
            screenshot("smoke-tracker.png");
            ui(() -> findText(tracker, "Punch out").performClick()); waitForIdleSync();
            await(() -> findText(tracker, "Punch in") != null && findText(tracker, "Start lunch/break") != null
                    && !new WorkLogStore(context).load().working(), "Punching out stops everything");
            ui(() -> findText(tracker, "Details").performClick()); waitForIdleSync();
            await(() -> findText(tracker, "Export CSV") != null, "Details shows the calendar and export");
            ActivityMonitor exportMonitor = addMonitor(ExportActivity.class.getName(), null, false);
            ui(() -> findText(tracker, "Export CSV").performClick());
            Activity export = waitForMonitorWithTimeout(exportMonitor, 15000); removeMonitor(exportMonitor);
            check(export != null, "Export CSV opens the export screen"); waitForIdleSync();
            await(() -> findText(export, "Recommended import settings") != null && findText(export, "Save CSV") != null,
                    "Export screen shows its options and import settings");
            screenshot("smoke-export.png"); ui(export::finish); waitForIdleSync();
            await(tracker::hasWindowFocus, "Work tracker is back in front after export");
            ActivityMonitor dayMonitor = addMonitor(WorkDayActivity.class.getName(), null, false);
            ui(() -> findText(tracker, String.valueOf(LocalDate.now().getDayOfMonth())).performClick());
            Activity dayView = waitForMonitorWithTimeout(dayMonitor, 15000); removeMonitor(dayMonitor);
            check(dayView != null, "A calendar day opens the day view"); waitForIdleSync();
            await(() -> findText(dayView, "+ Work") != null && findText(dayView, "+ Break") != null,
                    "Day view renders its candle graph and one row of add buttons");
            await(dayView::hasWindowFocus, "Day view is in front");
            screenshot("smoke-day.png"); ui(dayView::finish); waitForIdleSync();
            ui(() -> findText(tracker, "Schedule").performClick()); waitForIdleSync();
            await(() -> findText(tracker, "Set up schedule") != null || findText(tracker, "Edit schedule") != null,
                    "Schedule sub-room holds the work schedule");
            ui(tracker::finish); waitForIdleSync();

            Activity custom = startActivitySync(new Intent(context, CustomThemeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            int[] colours = {0xff101010, 0xff123456, 0xffab7612, 0xffefefef};
            String[] labels = {"Background colour", "Button colour", "Highlight colour", "Text colour"};
            ui(() -> {
                for (int i = 0; i < labels.length; i++) {
                    final String label = labels[i];
                    EditText field = (EditText) flatten(custom).stream().filter(v -> v instanceof EditText
                            && label.equals(String.valueOf(v.getContentDescription()))).findFirst().orElseThrow();
                    field.setText(String.format(java.util.Locale.ROOT, "#%06X", colours[i] & 0xffffff));
                }
                findText(custom, "Save theme").performClick();
            });
            waitForIdleSync();
            check(store.theme() == 3 && Arrays.equals(store.customColours(), colours), "All four custom colours persist");
            Activity customMain = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            Palette applied = ((MainActivity) customMain).palette;
            check(applied.background == colours[0] && applied.button == colours[1]
                    && applied.highlight == colours[2] && applied.text == colours[3], "Custom colours apply after reopening");
            screenshot("smoke-custom.png"); ui(customMain::finish); waitForIdleSync();
            result.putString("stream", "PASS: " + checks + " Android smoke checks\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n" + android.util.Log.getStackTraceString(failure));
        } finally {
            context.getSystemService(NotificationManager.class).cancelAll();
            new TaskStore(context).customColours(originalCustom);
            SharedPreferences.Editor restore = workLog.edit().clear();
            for (var entry : originalWorkLog.entrySet()) restore.putString(entry.getKey(), (String) entry.getValue());
            restore.commit();
            preferences.edit().putString("tasks", originalTasks).putInt("theme", originalTheme)
                    .putInt("next_id", originalNext).commit();
            Reminders.reconcile(context, false);
        }
        status.putString("stream", result.getString("stream"));
        sendStatus(resultCode == Activity.RESULT_OK ? 0 : -2, status);
        finish(resultCode, result);
    }

    private void ui(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("UI action failed", failure.get());
    }

    private void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        waitForIdleSync(); check(condition.getAsBoolean(), description);
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
             java.io.InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            stream.readAllBytes();
        }
    }
    private void screenshot(String filename) throws Exception {
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        try (java.io.OutputStream output = new java.io.FileOutputStream(
                new java.io.File(getTargetContext().getCacheDir(), filename))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
    private List<View> flatten(Activity activity) {
        List<View> views = new ArrayList<>(); collect(activity.getWindow().getDecorView(), views); return views;
    }
    private void collect(View view, List<View> all) {
        all.add(view);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), all);
        }
    }
    private TextView findTextStarting(Activity activity, String prefix) {
        return flatten(activity).stream().filter(v -> v instanceof TextView)
                .map(v -> (TextView) v).filter(v -> v.getText().toString().startsWith(prefix)).findFirst().orElse(null);
    }
    private TextView findText(Activity activity, String value) {
        return flatten(activity).stream().filter(v -> v instanceof TextView)
                .map(v -> (TextView) v).filter(v -> value.contentEquals(v.getText())).findFirst().orElse(null);
    }
    private void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
}
