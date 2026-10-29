package style.spacedragon.repeatremind;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The work tracker room: punch clock, a calendar of worked days, and the work schedule. */
final class WorkRoom {
    static final int TRACKER = 0, DETAILS = 1, SCHEDULE = 2;
    static final String[] NAMES = {"Tracker", "Details", "Schedule"};
    /** A day of this many hours or more gets the largest circle. */
    private static final double FULL_DAY_HOURS = 10;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM uuuu");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d");

    private final MainActivity a;
    private YearMonth month = YearMonth.now();
    private WorkLog log;
    private boolean stale = true;
    /** The tracker's totals card, refreshed in place each minute. */
    private LinearLayout clock;
    private LocalDate clockDay;
    /** A job picked while not punched in; it is saved with the next punch in, not when picked. */
    private int chosenJob;

    WorkRoom(MainActivity activity) { a = activity; }

    /** True while the tracker shows a running clock, so its totals update each minute. */
    boolean ticking(int sub) { return sub == TRACKER && log != null && log.working() && clock != null; }

    /** Reads the log again on the next render, e.g. after another screen may have changed it. */
    void invalidate() { stale = true; }

    /** Updates only the running totals, from the log already in memory: no reload, no rebuilt screen. */
    boolean tick() {
        if (clock == null || log == null) return false;
        fillClock();
        return log.working();
    }

    void render(FrameLayout body, int sub, Workdays schedule) {
        clock = null;
        ScrollView scroll = new ScrollView(a);
        LinearLayout content = a.column();
        content.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(24));
        scroll.addView(content);
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        // The schedule needs no log, but the bar's job button does.
        if (log == null || stale) {
            try {
                log = new WorkLogStore(a).load();
                stale = false;
            } catch (IllegalStateException e) {
                log = null;
                if (sub != SCHEDULE) {
                    content.addView(a.text("Work hours could not be read: " + e.getMessage(), 15, a.palette.text));
                    return;
                }
            }
        }
        if (log != null && chosenJob != 0 && log.activeJobs().contains(chosenJob)) log.selected = chosenJob;
        switch (sub) {
            case DETAILS -> details(content);
            case SCHEDULE -> schedule(content, schedule);
            default -> tracker(content);
        }
    }

    // ---- Tracker ----

    private void tracker(LinearLayout content) {
        int job = log.selected;
        // Each toggle acts on the selected job only; one that cannot apply yet is shown disabled, never changed for you.
        content.addView(toggle(log.working(job), true, "Punch out", "Punch in", () -> {
            if (log.working(job)) log.punchOut(job, now()); else log.punchIn(job, now(), zone());
        }), a.spaced());
        content.addView(toggle(log.running(WorkLog.Kind.PRODUCTIVE, job), log.canStart(WorkLog.Kind.PRODUCTIVE, job),
                "Stop productive", "Start productive", () -> {
            if (log.running(WorkLog.Kind.PRODUCTIVE, job)) log.stop(WorkLog.Kind.PRODUCTIVE, job, now());
            else log.start(WorkLog.Kind.PRODUCTIVE, job, now());
        }), a.spaced());
        content.addView(toggle(log.running(WorkLog.Kind.BREAK, job), log.canStart(WorkLog.Kind.BREAK, job),
                "Stop lunch/break", "Start lunch/break", () -> {
            if (log.running(WorkLog.Kind.BREAK, job)) log.stop(WorkLog.Kind.BREAK, job, now());
            else log.start(WorkLog.Kind.BREAK, job, now());
        }), a.spaced());
        String hint = !log.working(job) ? "Punch in to " + log.jobName(job) + " to start productive time or a break."
                : log.running(WorkLog.Kind.PRODUCTIVE, job) ? "Stop productive time to start a break."
                : log.running(WorkLog.Kind.BREAK, job) ? "Stop the break to start productive time." : null;
        if (hint != null) {
            TextView note = a.text(hint, 13, a.palette.secondary);
            note.setPadding(a.dp(4), 0, a.dp(4), a.dp(12));
            content.addView(note);
        }

        // A shift that runs past midnight keeps showing the day it started.
        var open = log.openWork(job);
        LocalDate day = open != null ? open.getKey() : LocalDate.now();
        clock = a.card(18);
        clockDay = day;
        fillClock();
        content.addView(clock, a.spaced());
        content.addView(a.button("View day", () -> openDay(day)));
    }

    private void fillClock() {
        LinearLayout card = clock;
        LocalDate day = clockDay;
        card.removeAllViews();
        WorkLog.Totals totals = log.totals(day, now());
        TextView title = a.text(day.equals(LocalDate.now()) ? "Today" : day.format(DAY), 19, a.palette.text);
        title.setTypeface(null, Typeface.BOLD); card.addView(title);
        List<Integer> working = log.workingJobs();
        if (working.isEmpty()) a.line(card, "Not punched in");
        for (int on : working) {
            a.line(card, log.jobName(on) + " · " + state(on) + " since " + Instant.ofEpochSecond(log.openWork(on).getValue().start)
                    .atZone(zone()).format(a.timeFormat()));
        }
        a.line(card, "Worked " + WorkCsv.clock(totals.worked()) + " · Break " + WorkCsv.clock(totals.breaks));
        a.line(card, "Productive " + WorkCsv.clock(totals.productive)
                + " · Unproductive " + WorkCsv.clock(totals.unproductive()));
        List<Integer> jobsToday = log.jobsOn(day);
        if (jobsToday.size() > 1) {
            for (int other : jobsToday) {
                a.line(card, log.jobName(other) + " · worked " + WorkCsv.clock(log.totals(day, now(), other).worked()));
            }
        }
    }

    /** "Punched in", "Productive" or "On break" for a punched-in job; "Off" otherwise. */
    private String state(int job) {
        return !log.working(job) ? "Off" : log.running(WorkLog.Kind.PRODUCTIVE, job) ? "Productive"
                : log.running(WorkLog.Kind.BREAK, job) ? "On break" : "Punched in";
    }

    /**
     * The current job, for the work room's bar. Tapping it rolls out the jobs, each with its state, and the job
     * manager; picking a job only chooses which one the toggles act on. Null while the log cannot be read.
     */
    Button jobButton() {
        if (log == null) return null;
        int selected = log.selected;
        Button drop = a.button(log.jobName(selected) + " \u25BE", null);
        drop.setSingleLine(true);
        drop.setEllipsize(TextUtils.TruncateAt.END);
        drop.setPadding(a.dp(6), a.dp(8), a.dp(6), a.dp(8));
        drop.setBackground(a.background(a.palette.surface, 12, true));
        drop.setTextColor(a.palette.text);
        drop.setTypeface(null, Typeface.BOLD);
        drop.setContentDescription("Job: " + log.jobName(selected) + ", " + state(selected) + ". Tap to change job");
        drop.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(a, v);
            for (int job : log.activeJobs()) {
                menu.getMenu().add(0, job, 0, log.jobName(job) + " · " + state(job)).setCheckable(true).setChecked(job == selected);
            }
            menu.getMenu().setGroupCheckable(0, true, true);
            menu.getMenu().add(1, WorkLog.ALL_JOBS, 1, "Manage jobs…");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getGroupId() == 1) manageJobs(); else pickJob(item.getItemId());
                return true;
            });
            menu.show();
        });
        return drop;
    }

    /** Chooses the job the toggles act on; nothing is punched in or out, and nothing is written. */
    private void pickJob(int job) {
        if (job == log.selected) return;
        chosenJob = job;
        log.selected = job;
        a.rerender();
    }

    private void manageJobs() {
        List<Integer> ids = new ArrayList<>(log.jobs.keySet());
        String[] names = new String[ids.size() + 1];
        for (int i = 0; i < ids.size(); i++) {
            WorkLog.Job job = log.jobs.get(ids.get(i));
            names[i] = job.name + (job.archived ? " (archived)" : "");
        }
        names[ids.size()] = "Add job";
        new AlertDialog.Builder(a).setTitle("Jobs")
                .setItems(names, (dialog, which) -> {
                    if (which == ids.size()) nameJob("Add job", "", name -> { chosenJob = log.addJob(name); log.selected = chosenJob; });
                    else jobOptions(ids.get(which));
                }).setNegativeButton("Close", null).show();
    }

    private void jobOptions(int job) {
        WorkLog.Job details = log.jobs.get(job);
        boolean used = log.used(job);
        String remove = details.archived ? "Restore" : used ? "Archive (keeps its recorded work)" : "Delete";
        new AlertDialog.Builder(a).setTitle(details.name)
                .setItems(new String[]{"Rename", remove}, (dialog, which) -> {
                    try {
                        if (which == 0) { nameJob("Rename job", details.name, name -> log.renameJob(job, name)); return; }
                        if (details.archived) log.restoreJob(job); else log.removeJob(job);
                        if (chosenJob == job) chosenJob = 0;
                        save();
                    } catch (IllegalArgumentException e) { a.message(e.getMessage()); }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Asks for a job name, then applies it and saves. */
    private void nameJob(String title, String current, java.util.function.Consumer<String> apply) {
        EditText field = a.input("Job name", current, 60, false);
        LinearLayout box = a.column();
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0);
        box.addView(field);
        new AlertDialog.Builder(a).setTitle(title).setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    try { apply.accept(field.getText().toString()); save(); }
                    catch (IllegalArgumentException e) { a.message(e.getMessage()); }
                }).show();
    }

    /** A toggle highlights in the selection colour while on; one that cannot be used yet is dimmed and disabled. */
    private Button toggle(boolean on, boolean usable, String onLabel, String offLabel, Runnable change) {
        Button toggle = a.button(on ? onLabel : offLabel, () -> {
            try { change.run(); }
            catch (IllegalArgumentException e) { a.message(e.getMessage()); return; }
            save();
        });
        toggle.setEnabled(on || usable);
        toggle.setAlpha(on || usable ? 1f : .45f);
        toggle.setSelected(on);
        toggle.setTextSize(17);
        toggle.setMinHeight(a.dp(64));
        toggle.setBackground(a.background(on ? a.palette.highlight : a.palette.button, 16, false));
        toggle.setTextColor(on ? a.palette.highlightText : a.palette.buttonText);
        return toggle;
    }

    private void save() {
        try { new WorkLogStore(a).save(log); }
        catch (RuntimeException e) { a.message("Work hours could not be saved: " + e.getMessage()); stale = true; }
        a.rerender();
    }

    // ---- Details: calendar ----

    private void details(LinearLayout content) {
        LinearLayout nav = new LinearLayout(a);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = a.button("‹", () -> { month = month.minusMonths(1); a.rerender(); });
        previous.setContentDescription("Previous month");
        Button next = a.button("›", () -> { month = month.plusMonths(1); a.rerender(); });
        next.setContentDescription("Next month");
        TextView label = a.text(month.format(MONTH), 18, a.palette.text);
        label.setGravity(Gravity.CENTER); label.setTypeface(null, Typeface.BOLD);
        nav.addView(previous, new LinearLayout.LayoutParams(a.dp(56), a.dp(48)));
        nav.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(next, new LinearLayout.LayoutParams(a.dp(56), a.dp(48)));
        content.addView(nav, a.spaced());

        LinearLayout names = new LinearLayout(a);
        for (int i = 0; i < 7; i++) {
            TextView name = a.text(DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    12, a.palette.secondary);
            name.setGravity(Gravity.CENTER);
            names.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        }
        content.addView(names);

        long now = now();
        LocalDate today = LocalDate.now();
        LocalDate first = month.atDay(1);
        int lead = first.getDayOfWeek().getValue() - 1;
        long monthWorked = 0, monthProductive = 0;
        java.util.TreeMap<Integer, Long> perJob = new java.util.TreeMap<>();
        int worked = 0;
        LinearLayout row = null;
        for (int cell = 0; cell < lead + month.lengthOfMonth(); cell++) {
            if (cell % 7 == 0) {
                row = new LinearLayout(a);
                content.addView(row);
            }
            if (cell < lead) {
                row.addView(new TextView(a), new LinearLayout.LayoutParams(0, a.dp(56), 1));
                continue;
            }
            LocalDate date = first.plusDays(cell - lead);
            WorkLog.Totals totals = log.totals(date, now);
            if (totals.punched > 0) {
                worked++;
                monthWorked += totals.worked();
                monthProductive += totals.productive;
                for (int job : log.jobsOn(date)) perJob.merge(job, log.totals(date, now, job).worked(), Long::sum);
            }
            row.addView(dayCell(date, totals, date.equals(today)), new LinearLayout.LayoutParams(0, a.dp(56), 1));
        }
        if (row != null) while (row.getChildCount() < 7) row.addView(new TextView(a), new LinearLayout.LayoutParams(0, a.dp(56), 1));

        LinearLayout summary = a.card(16);
        summary.addView(a.text(worked == 0 ? "No work recorded this month"
                : "Worked " + WorkCsv.clock(monthWorked) + " over " + worked + (worked == 1 ? " day" : " days"),
                17, a.palette.text));
        if (worked > 0) {
            a.line(summary, "Productive " + WorkCsv.clock(monthProductive) + " · "
                    + (monthWorked == 0 ? 0 : Math.round(100.0 * monthProductive / monthWorked)) + "% of worked time");
            // A line per job once more than one job has work this month.
            if (perJob.size() > 1) {
                for (var entry : perJob.entrySet()) {
                    a.line(summary, log.jobName(entry.getKey()) + " · worked " + WorkCsv.clock(entry.getValue()));
                }
            }
        }
        LinearLayout.LayoutParams gap = a.spaced();
        gap.topMargin = a.dp(12);
        content.addView(summary, gap);
        content.addView(a.button("Export CSV", () -> a.launch(new Intent(a, ExportActivity.class)
                .putExtra("month", month.toString()))));
    }

    /** A day number with a circle sized by hours worked; tapping opens the day. */
    private Button dayCell(LocalDate date, WorkLog.Totals totals, boolean today) {
        Button cell = new Button(a);
        cell.setText(String.valueOf(date.getDayOfMonth()));
        cell.setTextSize(12);
        cell.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        cell.setPadding(0, a.dp(2), 0, 0);
        cell.setAllCaps(false);
        cell.setMinWidth(0); cell.setMinimumWidth(0); cell.setMinHeight(0); cell.setMinimumHeight(0);
        cell.setTextColor(today ? a.palette.text : a.palette.secondary);
        if (today) cell.setTypeface(null, Typeface.BOLD);
        double hours = totals.worked() / 3600.0;
        float fraction = totals.punched == 0 ? 0 : (float) Math.max(0.15, Math.min(1, hours / FULL_DAY_HOURS));
        cell.setBackground(new Dot(fraction, today));
        cell.setContentDescription(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")) + ", "
                + (totals.punched == 0 ? "no work" : WorkCsv.clock(totals.worked()) + " worked"));
        cell.setOnClickListener(v -> openDay(date));
        return cell;
    }

    /** A circle in the lower part of a calendar cell; its diameter grows with the hours worked. */
    private final class Dot extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float fraction;
        private final boolean today;

        Dot(float fraction, boolean today) { this.fraction = fraction; this.today = today; }

        @Override public void draw(Canvas canvas) {
            float cx = getBounds().exactCenterX(), cy = getBounds().top + a.dp(34);
            float largest = a.dp(19);
            if (fraction > 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(a.palette.highlight);
                canvas.drawCircle(cx, cy, largest * fraction, paint);
            }
            if (today) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(a.dp(1.5f));
                paint.setColor(a.palette.border);
                canvas.drawCircle(cx, cy, largest, paint);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void openDay(LocalDate date) {
        a.launch(new Intent(a, WorkDayActivity.class).putExtra("date", date.toString()));
    }

    void save(android.os.Bundle state) {
        state.putString("month", month.toString());
        state.putInt("chosenJob", chosenJob);
    }

    void restore(android.os.Bundle state) {
        month = YearMonth.parse(state.getString("month", YearMonth.now().toString()));
        chosenJob = state.getInt("chosenJob", 0);
    }

    // ---- Schedule ----

    private void schedule(LinearLayout content, Workdays work) {
        if (work == null) {
            TextView intro = a.text("Set your working days or shifts, prep time and commute. A reminder goes off "
                    + "before you need to start getting ready.", 15, a.palette.secondary);
            intro.setPadding(a.dp(4), a.dp(8), a.dp(4), a.dp(16));
            content.addView(intro);
            content.addView(a.button("Set up schedule", this::editSchedule));
            return;
        }
        ZoneId zone = zone();
        LocalDate today = LocalDate.now();
        DateTimeFormatter time = a.timeFormat();
        LinearLayout summary = a.card(18);
        TextView title = a.text(work.mode == Workdays.Mode.REGULAR ? "Regular schedule" : "Shift work", 19, a.palette.text);
        title.setTypeface(null, Typeface.BOLD); summary.addView(title);
        if (work.mode == Workdays.Mode.REGULAR) {
            a.line(summary, ScheduleText.weekdays(work.weekdays));
            a.line(summary, WorkText.hours(work.regular, time));
        }
        a.line(summary, WorkText.lead(work));
        Workdays.Occurrence next = work.reminder ? work.nextReminder(Instant.now(), zone) : null;
        a.line(summary, !work.reminder ? "Before-work reminder off" : next == null ? "No upcoming reminder"
                : "Next reminder · " + next.remindAt.atZone(zone)
                .format(DateTimeFormatter.ofPattern("EEE, MMM d · " + a.timePattern())));
        content.addView(summary, a.spaced());
        if (work.mode == Workdays.Mode.SHIFTS && work.shifts.subMap(today, today.plusDays(7)).isEmpty()) {
            TextView prompt = a.text("No shifts entered for the next 7 days. Add your times for each day you work.",
                    15, a.palette.text);
            prompt.setPadding(a.dp(4), 0, a.dp(4), a.dp(8));
            content.addView(prompt);
            content.addView(a.button("Enter shifts", this::editSchedule), a.spaced());
        }
        content.addView(a.label("Next 14 days"));
        boolean any = false;
        for (int i = 0; i < 14; i++) {
            LocalDate date = today.plusDays(i);
            Workdays.Shift shift = work.shiftOn(date);
            if (shift == null) continue;
            any = true;
            LinearLayout day = a.card(14);
            day.addView(a.text(date.format(DAY), 17, a.palette.text));
            a.line(day, WorkText.hours(shift, time));
            content.addView(day, a.spaced());
        }
        if (!any) content.addView(a.text("No work days", 15, a.palette.secondary), a.spaced());
        content.addView(a.button("Edit schedule", this::editSchedule));
    }

    private void editSchedule() { a.launch(new Intent(a, WorkdaysActivity.class)); }

    private static long now() { return System.currentTimeMillis() / 1000; }

    private static ZoneId zone() { return ZoneId.systemDefault(); }
}
