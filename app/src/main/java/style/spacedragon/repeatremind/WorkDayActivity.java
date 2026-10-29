package style.spacedragon.repeatremind;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One day of work as a candle graph: work, productive over unproductive time, and lunch/breaks in three columns. */
public final class WorkDayActivity extends BaseActivity {
    private static final String[] KIND_NAMES = {"Work", "Productive", "Lunch/break"};
    private LocalDate day;
    private WorkLog log;
    private Candle candle;
    private TextView summary;
    /** The user's time format, and the same without AM/PM for narrow candles. */
    private DateTimeFormatter time, compact;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            day = LocalDate.parse(getIntent().getStringExtra("date"));
            log = new WorkLogStore(this).load();
            render();
        } catch (RuntimeException e) {
            failOpen("This day could not be opened.");
        }
    }

    private void render() {
        time = timeFormat();
        compact = DateTimeFormatter.ofPattern(timePattern().replace("a", "").trim());
        LinearLayout root = column();
        root.addView(header(day.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), button("Done", this::finish)));
        LinearLayout top = column();
        top.setPadding(dp(20), 0, dp(20), 0);
        summary = text("", 14, palette.secondary);
        summary.setPadding(dp(4), 0, dp(4), dp(6));
        top.addView(summary);
        top.addView(legend());
        root.addView(top);
        // The graph takes all the room left between the totals and the buttons, and scrolls when it needs more.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setPadding(dp(12), 0, dp(20), 0);
        candle = new Candle(this);
        candle.setContentDescription("Timeline of this day. Tap a candle for its details; drag or tap a handle to change a time; "
                + "hold a handle on its neighbour's to merge them; swipe sideways across a candle to cut it in two.");
        scroll.addView(candle);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(20), dp(8), dp(20), dp(16));
        addAction(actions, "+ Work", "Add work period", this::addWork);
        addAction(actions, "+ Productive", "Add productive time", () -> addInside(WorkLog.Kind.PRODUCTIVE));
        addAction(actions, "+ Break", "Add lunch or break", () -> addInside(WorkLog.Kind.BREAK));
        root.addView(actions);
        install(root);
        refresh();
    }

    private void addAction(LinearLayout row, String value, String description, Runnable click) {
        Button action = button(value, click);
        action.setSingleLine(true);
        action.setPadding(dp(4), dp(8), dp(4), dp(8));
        action.setContentDescription(description);
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) cell.setMarginStart(dp(8));
        row.addView(action, cell);
    }

    private LinearLayout legend() {
        LinearLayout legend = new LinearLayout(this);
        legend.setPadding(0, 0, 0, dp(8));
        int[] colours = {palette.button, palette.highlight, palette.surface, breakColour()};
        String[] names = {"Work", "Productive", "Unproductive", "Break"};
        for (int i = 0; i < 4; i++) {
            TextView key = text(names[i], 12, palette.secondary);
            key.setCompoundDrawablesRelative(swatch(colours[i]), null, null, null);
            key.setCompoundDrawablePadding(dp(4));
            legend.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return legend;
    }

    private android.graphics.drawable.Drawable swatch(int colour) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(colour); shape.setCornerRadius(dp(3)); shape.setStroke(dp(1), palette.border);
        shape.setBounds(0, 0, dp(12), dp(12));
        return shape;
    }

    private int breakColour() { return ColourMath.blend(palette.surface, palette.secondary, .45); }

    /** Updates the totals and redraws the graph. */
    private void refresh() {
        WorkLog.Totals totals = log.totals(day, now());
        summary.setText(totals.punched == 0 ? "No work recorded. Add a work period to start."
                : "Worked " + WorkCsv.clock(totals.worked()) + " · Productive " + WorkCsv.clock(totals.productive)
                + " · Unproductive " + WorkCsv.clock(totals.unproductive()) + " · Break " + WorkCsv.clock(totals.breaks));
        candle.requestLayout();
        candle.invalidate();
    }

    private String name(WorkLog.Period period) { return KIND_NAMES[period.kind.ordinal()]; }

    private String range(WorkLog.Period period) {
        return clock(period.start) + "–" + (period.open() ? "now" : clock(period.end));
    }

    private String clock(long seconds) { return local(seconds).format(time); }

    private ZonedDateTime local(long seconds) { return Instant.ofEpochSecond(seconds).atZone(zone()); }

    private void save() {
        try { new WorkLogStore(this).save(log); }
        catch (RuntimeException e) { error(e); }
        refresh();
    }

    // ---- Details ----

    /**
     * Everything about one period: its job, start and end with their dates, its length and, for work, the time
     * inside it. Start and end change from here too; each child opens its own details.
     */
    private void details(WorkLog.Period period) {
        long now = now();
        LinearLayout box = column();
        box.setPadding(dp(20), dp(4), dp(20), dp(8));
        AlertDialog[] shown = new AlertDialog[1];
        Runnable close = () -> shown[0].dismiss();
        TextView owner = text(log.jobName(period.job), 15, palette.secondary);
        owner.setPadding(0, 0, 0, dp(8));
        box.addView(owner);
        box.addView(detail("Start", clock(period.start), date(period.start), "Change", () -> { close.run(); pickTime(period, true); }));
        if (period.open()) box.addView(detail("End", "Running", "Running for " + WorkCsv.clock(period.length(now)), null, null));
        else box.addView(detail("End", clock(period.end), date(period.end), "Change", () -> { close.run(); pickTime(period, false); }));
        box.addView(detail("Length", WorkCsv.clock(period.length(now)), null, null, null));
        if (period.kind == WorkLog.Kind.WORK) {
            List<WorkLog.Period> children = log.children(day, period);
            long productive = 0, breaks = 0;
            for (WorkLog.Period child : children) {
                if (child.kind == WorkLog.Kind.BREAK) breaks += child.length(now); else productive += child.length(now);
            }
            long worked = period.length(now) - breaks;
            box.addView(detail("Worked", WorkCsv.clock(worked), "Productive " + WorkCsv.clock(productive)
                    + " · Unproductive " + WorkCsv.clock(worked - productive) + " · Break " + WorkCsv.clock(breaks), null, null));
            if (!children.isEmpty()) box.addView(label("Inside this work"));
            for (WorkLog.Period child : children) {
                Button row = button(name(child) + " · " + range(child) + " (" + WorkCsv.clock(child.length(now)) + ")",
                        () -> { close.run(); details(child); });
                row.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
                row.setBackground(background(palette.surface, 12, true));
                row.setTextColor(palette.text);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = dp(6);
                box.addView(row, params);
            }
        } else {
            WorkLog.Period parent = log.parent(day, period);
            if (parent != null) box.addView(detail("Within", range(parent), "Work for " + log.jobName(parent.job), "Open",
                    () -> { close.run(); details(parent); }));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(name(period) + " · " + WorkLog.shortName(log.jobName(period.job))).setView(scroll)
                .setNegativeButton("Close", null)
                .setNeutralButton("Delete", (dialog, which) -> confirmDelete(period));
        if (period.kind == WorkLog.Kind.WORK && log.activeJobs().size() > 1) {
            builder.setPositiveButton("Change job", (dialog, which) -> pickJob("Job for this work", period.job, chosen -> {
                try { log.setJob(day, period, chosen, now()); save(); }
                catch (IllegalArgumentException e) { message(e.getMessage()); }
            }));
        }
        shown[0] = builder.show();
    }

    /** A labelled value with an optional note under it and an optional action beside it. */
    private LinearLayout detail(String name, String value, String note, String action, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView key = text(name, 13, palette.secondary);
        row.addView(key, new LinearLayout.LayoutParams(dp(72), -2));
        LinearLayout values = column();
        TextView main = text(value, 17, palette.text);
        main.setTypeface(null, android.graphics.Typeface.BOLD);
        values.addView(main);
        if (note != null) values.addView(text(note, 13, palette.secondary));
        row.addView(values, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            Button change = button(action, click);
            change.setMinHeight(dp(40)); change.setMinimumHeight(dp(40));
            change.setPadding(dp(12), dp(4), dp(12), dp(4));
            row.addView(change);
        }
        return row;
    }

    private String date(long seconds) { return local(seconds).format(DateTimeFormatter.ofPattern("EEEE, MMM d")); }

    private void confirmDelete(WorkLog.Period period) {
        String message = period.kind == WorkLog.Kind.WORK
                ? "This also deletes the productive and break time inside it." : null;
        new AlertDialog.Builder(this).setTitle("Delete " + name(period).toLowerCase() + "?").setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> { log.remove(day, period); save(); }).show();
    }

    /** Minute-precise entry with the system time picker; the result is fitted to the neighbouring periods. */
    private void pickTime(WorkLog.Period period, boolean start) {
        ZonedDateTime current = local(start ? period.start : period.end);
        new TimePickerDialog(this, (picker, hour, minute) -> {
            // Times are read on the day the period starts; an end at or before the start means the next day,
            // e.g. a night shift. Using the current end's date would turn a shortened night shift into 24 hours.
            LocalDate base = local(period.start).toLocalDate();
            LocalDateTime picked = base.atTime(hour, minute);
            if (!start && !picked.atZone(zone()).isAfter(local(period.start))) picked = picked.plusDays(1);
            long requested = picked.atZone(zone()).toEpochSecond();
            long applied = log.move(day, period, start, requested, now(), zone());
            if (applied != requested) {
                Toast.makeText(this, "Adjusted to " + clock(applied) + " to fit the other periods", Toast.LENGTH_LONG).show();
            }
            save();
        }, current.getHour(), current.getMinute(), android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void addWork() {
        pickJob("Add work for which job?", WorkLog.ALL_JOBS, job -> {
            try { log.addWork(day, now(), zone(), job); save(); }
            catch (IllegalArgumentException e) { message(e.getMessage()); }
        });
    }

    /** Asks which active job to use, or uses the only one without asking. */
    private void pickJob(String title, int current, java.util.function.IntConsumer picked) {
        List<Integer> jobs = log.activeJobs();
        if (jobs.size() == 1 && current == WorkLog.ALL_JOBS) { picked.accept(jobs.get(0)); return; }
        String[] names = new String[jobs.size()];
        for (int i = 0; i < names.length; i++) names[i] = log.jobName(jobs.get(i));
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names, jobs.indexOf(current), (dialog, which) -> {
                    dialog.dismiss();
                    picked.accept(jobs.get(which));
                }).setNegativeButton("Cancel", null).show();
    }

    private void addInside(WorkLog.Kind kind) {
        List<WorkLog.Period> work = log.work(day);
        if (work.isEmpty()) { message("Add a work period first"); return; }
        if (work.size() == 1) { addInside(work.get(0), kind); return; }
        String[] names = new String[work.size()];
        for (int i = 0; i < names.length; i++) names[i] = log.jobName(work.get(i).job) + " · " + range(work.get(i));
        new AlertDialog.Builder(this).setTitle("Add to which work period?")
                .setItems(names, (dialog, which) -> addInside(work.get(which), kind))
                .setNegativeButton("Cancel", null).show();
    }

    private void addInside(WorkLog.Period work, WorkLog.Kind kind) {
        try { log.addInside(day, work, kind, now()); save(); }
        catch (IllegalArgumentException e) { message(e.getMessage()); }
    }

    private static long now() { return System.currentTimeMillis() / 1000; }

    private static ZoneId zone() { return ZoneId.systemDefault(); }

    // ---- Graph ----

    /** A drag handle at one edge of a period. */
    private static final class Handle {
        final WorkLog.Period period;
        final boolean start;
        float x, y;
        int column;

        Handle(WorkLog.Period period, boolean start) { this.period = period; this.start = start; }
    }

    /**
     * Three candle columns on a time axis: work, productive (over unproductive work time) and breaks. Dragging a
     * handle moves its edge in five-minute steps, and holding it on a neighbour's handle merges the two; tapping a
     * handle opens the time picker; tapping a candle opens its details; swiping across a candle cuts it in two.
     */
    private final class Candle extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG), jobLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Handle> handles = new ArrayList<>();
        private final List<RectF> bars = new ArrayList<>();
        private final List<WorkLog.Period> barPeriods = new ArrayList<>();
        private final RectF rect = new RectF();
        private final int slop;
        private long rangeStart, rangeEnd;
        private Handle active;
        private List<Integer> lanes = java.util.Collections.singletonList(WorkLog.FIRST_JOB);
        private WorkLog.Period tapped;
        private float downX, downY, swipeX;
        private boolean moved, swiping, done;
        private float hourHeight;
        /** While a dragged handle rests on a neighbour's, the neighbour it will merge with after a short hold. */
        private WorkLog.Period mergeWith;
        private final Runnable merge = this::mergeHeld;

        Candle(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        private float top() { return dp(30); }

        /** Tall enough for a candle's start, short name and end, with the handles clear of the text. */
        private float minCandle() { return dp(64); }

        /** Whole hours around the recorded periods, at least eight; 8:00–18:00 on an empty day. */
        private void computeRange() {
            long dayStart = WorkLog.dayStart(day, zone()), now = now();
            long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
            for (WorkLog.Period period : log.periods(day)) {
                first = Math.min(first, period.start);
                last = Math.max(last, period.end(now));
            }
            if (first == Long.MAX_VALUE) { first = dayStart + 8 * 3600; last = dayStart + 18 * 3600; }
            long startHour = Math.max(0, Math.floorDiv(first - dayStart, 3600) - 1);
            long endHour = Math.max(startHour + 8, Math.floorDiv(last - dayStart + 3599, 3600) + 1);
            rangeStart = dayStart + startHour * 3600;
            rangeEnd = dayStart + endHour * 3600;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            // The range and scale stay fixed while a handle is being dragged.
            float hours = (rangeEnd - rangeStart) / 3600f;
            if (active == null) {
                computeRange();
                hours = (rangeEnd - rangeStart) / 3600f;
                // Hours stretch so the shortest finished period can show its times and name, within reason;
                // anything shorter still is drawn at the minimum size.
                long shortest = Long.MAX_VALUE;
                for (WorkLog.Period period : log.periods(day)) {
                    if (!period.open() && period.length(0) > 0) shortest = Math.min(shortest, period.length(0));
                }
                float scale = shortest == Long.MAX_VALUE ? dp(60) : minCandle() * 3600f / shortest;
                hourHeight = Math.max(dp(60), Math.min(dp(160), scale));
                // Fill the screen when the day is short.
                int room = MeasureSpec.getSize(heightSpec);
                if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
                    hourHeight = Math.max(hourHeight, (room - top() * 2) / hours);
                }
            }
            int height = Math.round(top() * 2 + hours * hourHeight);
            if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) height = Math.max(height, MeasureSpec.getSize(heightSpec));
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
        }

        private float y(long seconds) { return top() + (seconds - rangeStart) / 3600f * hourHeight; }

        private long seconds(float y) { return rangeStart + Math.round((y - top()) / hourHeight * 3600); }

        private static final int COLUMNS = 3;
        private static final long MERGE_HOLD_MS = 600;
        private final String[] titles = {"Work", "Productive", "Break"};

        private float axis() { return dp(56); }
        private float gap() { return dp(10); }
        private float columnWidth() { return (getWidth() - axis() - gap() * (COLUMNS + 1)) / COLUMNS; }
        private float column(int index) { return axis() + gap() + index * (columnWidth() + gap()); }
        private float handleRadius() { return dp(9); }

        /** Outline shades: the usual border, and two slightly different contrasts for candles whose handles overlap. */
        private int outline(int shade) {
            return shade == 0 ? palette.border : ColourMath.blend(palette.border, palette.text, shade == 1 ? .35 : .75);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            handles.clear(); bars.clear(); barPeriods.clear();
            long now = now();
            label.setColor(palette.secondary);
            label.setTextSize(dp(12));
            label.setTextAlign(Paint.Align.LEFT);
            stroke.setStyle(Paint.Style.STROKE);
            for (long hour = rangeStart; hour <= rangeEnd; hour += 3600) {
                float y = y(hour);
                stroke.setColor(palette.border); stroke.setStrokeWidth(dp(0.5f)); stroke.setAlpha(90);
                canvas.drawLine(axis(), y, getWidth(), y, stroke);
                canvas.drawText(clock(hour), 0, y + dp(4), label);
            }
            stroke.setAlpha(255);
            label.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < COLUMNS; i++) canvas.drawText(titles[i], column(i) + columnWidth() / 2, dp(16), label);

            // Candles: work; productive over unproductive work time; breaks in their own column.
            float radius = dp(8);
            // Jobs can overlap in time, so each column has a lane per job worked that day.
            lanes = log.jobsOn(day);
            if (lanes.isEmpty()) lanes = java.util.Collections.singletonList(WorkLog.FIRST_JOB);
            for (WorkLog.Period work : log.work(day)) {
                candle(work, 0, now);
                List<WorkLog.Period> children = log.children(day, work);
                // Unproductive time is the work period outside breaks; productive time is drawn over it.
                long from = work.start;
                for (WorkLog.Period child : children) {
                    if (child.kind != WorkLog.Kind.BREAK) continue;
                    track(canvas, from, child.start, work.job);
                    from = Math.max(from, child.end(now));
                }
                track(canvas, from, work.end(now), work.job);
                // The break column shows the work period faintly, for context.
                rect.set(lane(2, work.job), y(work.start), lane(2, work.job) + laneWidth(), y(work.end(now)));
                stroke.setColor(palette.border); stroke.setStrokeWidth(dp(1)); stroke.setAlpha(110);
                canvas.drawRoundRect(rect, radius, radius, stroke);
                stroke.setAlpha(255);
                for (WorkLog.Period child : children) candle(child, child.kind == WorkLog.Kind.PRODUCTIVE ? 1 : 2, now);
            }
            Map<WorkLog.Period, Integer> shades = spread();
            for (int i = 0; i < bars.size(); i++) {
                WorkLog.Period period = barPeriods.get(i);
                fill.setColor(period.kind == WorkLog.Kind.WORK ? palette.button
                        : period.kind == WorkLog.Kind.PRODUCTIVE ? palette.highlight : breakColour());
                canvas.drawRoundRect(bars.get(i), radius, radius, fill);
                int shade = shades.getOrDefault(period, 0);
                stroke.setColor(outline(shade)); stroke.setStrokeWidth(dp(shade == 0 ? 1 : 2));
                canvas.drawRoundRect(bars.get(i), radius, radius, stroke);
                labels(canvas, bars.get(i), period, now);
            }
            if (log.periods(day).stream().anyMatch(WorkLog.Period::open) && now >= rangeStart && now <= rangeEnd) {
                stroke.setColor(palette.text); stroke.setStrokeWidth(dp(1.5f));
                canvas.drawLine(axis(), y(now), getWidth(), y(now), stroke);
            }
            // Handles are one solid colour; an overlapping one gets a ring in its candle's outline shade.
            float r = handleRadius();
            if (swiping) {
                stroke.setColor(palette.text); stroke.setStrokeWidth(dp(2));
                canvas.drawLine(downX, downY, swipeX, downY, stroke);
            }
            for (Handle handle : handles) {
                // The handles about to merge get a highlight ring while the hold runs.
                if (mergeWith != null && active != null && (handle.period == mergeWith || handle.period == active.period)
                        && Math.abs(handle.y - y(active.start ? active.period.start : active.period.end)) < 1) {
                    stroke.setColor(palette.highlight); stroke.setStrokeWidth(dp(3));
                    canvas.drawCircle(handle.x, handle.y, r + dp(7), stroke);
                }
                int shade = shades.getOrDefault(handle.period, 0);
                if (shade != 0) {
                    fill.setColor(outline(shade));
                    canvas.drawCircle(handle.x, handle.y, r + dp(4), fill);
                    fill.setColor(palette.background);
                    canvas.drawCircle(handle.x, handle.y, r + dp(2), fill);
                }
                fill.setColor(palette.text);
                canvas.drawCircle(handle.x, handle.y, r, fill);
            }
        }

        /** The start at the top of a candle, the job's short name in the middle and the end at the bottom. */
        private void labels(Canvas canvas, RectF bar, WorkLog.Period period, long now) {
            jobLabel.setColor(period.kind == WorkLog.Kind.WORK ? palette.buttonText
                    : period.kind == WorkLog.Kind.PRODUCTIVE ? palette.highlightText : palette.text);
            jobLabel.setTextAlign(Paint.Align.CENTER);
            float width = bar.width() - dp(4), inset = handleRadius() + dp(2);
            String end = period.open() ? "now" : clock(period.end);
            String endShort = period.open() ? "now" : local(period.end).format(compact);
            fit(canvas, clock(period.start), local(period.start).format(compact), bar.centerX(), bar.top + inset, width, -1);
            fit(canvas, WorkLog.shortName(log.jobName(period.job)), null, bar.centerX(), bar.centerY(), width, 0);
            fit(canvas, end, endShort, bar.centerX(), bar.bottom - inset, width, 1);
        }

        /**
         * Draws text centred on x, hanging below y (-1), centred on it (0) or resting above it (1). It shrinks to
         * fit the width, then tries the shorter form; text that still does not fit is left out.
         */
        private void fit(Canvas canvas, String text, String shorter, float x, float y, float width, int place) {
            for (String candidate : shorter == null ? new String[]{text} : new String[]{text, shorter}) {
                for (int size = 12; size >= 9; size--) {
                    jobLabel.setTextSize(dp(size));
                    jobLabel.setFakeBoldText(place == 0);
                    if (jobLabel.measureText(candidate) > width) continue;
                    Paint.FontMetrics metrics = jobLabel.getFontMetrics();
                    float baseline = place < 0 ? y - metrics.ascent : place > 0 ? y - metrics.descent
                            : y - (metrics.ascent + metrics.descent) / 2;
                    canvas.drawText(candidate, x, baseline, jobLabel);
                    return;
                }
            }
        }

        /** Unproductive time in the productive column. */
        private void track(Canvas canvas, long from, long to, int job) {
            if (to <= from) return;
            rect.set(lane(1, job), y(from), lane(1, job) + laneWidth(), y(to));
            fill.setColor(palette.surface);
            canvas.drawRoundRect(rect, dp(8), dp(8), fill);
            stroke.setColor(palette.border); stroke.setStrokeWidth(dp(1));
            canvas.drawRoundRect(rect, dp(8), dp(8), stroke);
        }

        /** Records a candle and its handles, centred on the candle; a running period has no end handle. */
        private void candle(WorkLog.Period period, int column, long now) {
            float left = lane(column, period.job), top = y(period.start);
            float bottom = Math.max(y(period.end(now)), top + minCandle());
            bars.add(new RectF(left, top, left + laneWidth(), bottom));
            barPeriods.add(period);
            float centre = left + laneWidth() / 2;
            // Handles group by column and lane, since only handles in the same lane can collide.
            int group = column * 1000 + Math.max(0, lanes.indexOf(period.job));
            Handle start = new Handle(period, true);
            start.x = centre; start.y = top; start.column = group;
            handles.add(start);
            if (!period.open()) {
                Handle end = new Handle(period, false);
                end.x = centre; end.y = bottom; end.column = group;
                handles.add(end);
            }
        }

        private float laneGap() { return dp(4); }

        private float laneWidth() { return (columnWidth() - laneGap() * (lanes.size() - 1)) / lanes.size(); }

        private float lane(int column, int job) {
            return column(column) + Math.max(0, lanes.indexOf(job)) * (laneWidth() + laneGap());
        }

        /**
         * Moves handles that would overlap in a column apart sideways, and gives each period in such a group
         * its own outline shade, so a handle can be matched to its candle.
         */
        private Map<WorkLog.Period, Integer> spread() {
            Map<WorkLog.Period, Integer> shades = new HashMap<>();
            float apart = handleRadius() * 2 + dp(6);
            java.util.Set<Integer> groups = new java.util.TreeSet<>();
            for (Handle handle : handles) groups.add(handle.column);
            for (int group : groups) {
                List<Handle> inColumn = new ArrayList<>();
                for (Handle handle : handles) if (handle.column == group) inColumn.add(handle);
                inColumn.sort((a, b) -> Float.compare(a.y, b.y));
                for (int i = 0; i < inColumn.size(); ) {
                    int j = i + 1;
                    while (j < inColumn.size() && inColumn.get(j).y - inColumn.get(j - 1).y < apart) j++;
                    int size = j - i;
                    if (size > 1) {
                        for (int k = 0; k < size; k++) {
                            Handle handle = inColumn.get(i + k);
                            handle.x += (k - (size - 1) / 2f) * apart;
                            shades.putIfAbsent(handle.period, 1 + k % 2);
                        }
                    }
                    i = j;
                }
            }
            return shades;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    active = nearest(x, y);
                    tapped = null;
                    downX = x; downY = y; swipeX = x;
                    moved = false; swiping = false; done = false;
                    if (active != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } else {
                        for (int i = bars.size() - 1; i >= 0; i--) {
                            if (bars.get(i).contains(x, y)) { tapped = barPeriods.get(i); break; }
                        }
                    }
                    // Taken even off the candles, so a swipe can start beside the one it cuts.
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (done) return true;
                    float dx = Math.abs(x - downX), dy = Math.abs(y - downY);
                    if (dx > slop || dy > slop) moved = true;
                    if (active != null && moved) {
                        long to = WorkLog.snap(Math.max(rangeStart, Math.min(rangeEnd, seconds(y))));
                        log.move(day, active.period, active.start, to, now(), zone());
                        holdMerge(log.touching(day, active.period, active.start));
                        invalidate();
                    } else if (active == null && (swiping || dx > slop && dx > dy * 2)) {
                        // A sideways swipe; vertical drags are left to the scroll view.
                        swiping = true;
                        swipeX = x;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    holdMerge(null);
                    if (done) {
                        done = false;
                    } else if (active != null) {
                        Handle handle = active;
                        active = null;
                        if (moved) save(); else pickTime(handle.period, handle.start);
                    } else if (swiping) {
                        swiping = false;
                        cut(Math.min(downX, x), Math.max(downX, x), downY);
                        invalidate();
                    } else if (tapped != null && !moved) {
                        performClick();
                        details(tapped);
                    }
                    tapped = null;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    holdMerge(null);
                    if (active != null && moved && !done) save();
                    active = null; tapped = null; swiping = false; done = false;
                    invalidate();
                    return true;
                }
                default -> { return super.onTouchEvent(event); }
            }
        }

        /** Starts, keeps or cancels the hold that merges the dragged period with the one its handle rests on. */
        private void holdMerge(WorkLog.Period target) {
            if (target == mergeWith) return;
            removeCallbacks(merge);
            mergeWith = target;
            if (target != null) postDelayed(merge, MERGE_HOLD_MS);
        }

        private void mergeHeld() {
            if (active == null || mergeWith == null) return;
            WorkLog.Period merged;
            try { merged = log.merge(day, active.period, mergeWith); }
            catch (IllegalArgumentException e) { message(e.getMessage()); return; }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            // The rest of this touch does nothing more; lifting the finger just ends it.
            active = null; mergeWith = null; done = true;
            save();
            Toast.makeText(getContext(), "Merged into " + name(merged).toLowerCase() + " " + range(merged), Toast.LENGTH_SHORT).show();
        }

        /**
         * Cuts the candle a sideways swipe crossed at the swipe's height. Work wins when the swipe crosses
         * several, since cutting work also cuts what is inside it.
         */
        private void cut(float from, float to, float y) {
            WorkLog.Period target = null;
            for (int i = 0; i < bars.size(); i++) {
                RectF bar = bars.get(i);
                if (y <= bar.top || y >= bar.bottom || from > bar.centerX() || to < bar.centerX()) continue;
                WorkLog.Period period = barPeriods.get(i);
                if (target == null || period.kind == WorkLog.Kind.WORK && target.kind != WorkLog.Kind.WORK) target = period;
            }
            if (target == null) return;
            long at = WorkLog.snap(seconds(y));
            try { log.split(day, target, at, now()); }
            catch (IllegalArgumentException e) { message(e.getMessage()); return; }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            save();
            Toast.makeText(getContext(), "Cut " + name(target).toLowerCase() + " at " + clock(at), Toast.LENGTH_SHORT).show();
        }

        @Override public boolean performClick() { return super.performClick(); }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(merge);
            super.onDetachedFromWindow();
        }

        private Handle nearest(float x, float y) {
            Handle best = null;
            float bestDistance = dp(26);
            for (Handle handle : handles) {
                float distance = (float) Math.hypot(handle.x - x, handle.y - y);
                if (distance < bestDistance) { best = handle; bestDistance = distance; }
            }
            return best;
        }
    }
}
