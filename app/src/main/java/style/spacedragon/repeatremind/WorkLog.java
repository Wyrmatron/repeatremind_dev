package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Punch clock: work periods, with productive and break periods inside them. Times are epoch seconds.
 * Independent of Android for deterministic tests.
 */
final class WorkLog {
    enum Kind { WORK, PRODUCTIVE, BREAK }

    /** End of a period that is still running. */
    static final long OPEN = 0;
    /** Dragging a handle moves it in five-minute steps; the time picker gives minute precision. */
    static final long STEP = 5 * 60;
    static final long MIN_LENGTH = 60;
    private static final long DAY = 24 * 60 * 60;
    /** Work recorded before jobs existed belongs to this job. */
    static final int FIRST_JOB = 1;
    static final int ALL_JOBS = 0;

    static final class Job {
        String name;
        /** Hidden from the tracker; kept so recorded work still shows its name. */
        boolean archived;

        Job(String name, boolean archived) { this.name = name; this.archived = archived; }
    }

    static final class Period {
        final Kind kind;
        long start, end;
        /** The job this period belongs to; productive and break periods share their work period's job. */
        int job = FIRST_JOB;

        Period(Kind kind, long start, long end) { this.kind = kind; this.start = start; this.end = end; }

        Period(Kind kind, long start, long end, int job) { this(kind, start, end); this.job = job; }

        boolean open() { return end == OPEN; }

        long end(long now) { return open() ? Math.max(start, now) : end; }

        long length(long now) { return end(now) - start; }
    }

    /** Hours for one day, in seconds. Worked excludes breaks; unproductive is worked time that was not productive. */
    static final class Totals {
        long punched, breaks, productive;

        long worked() { return Math.max(0, punched - breaks); }

        long unproductive() { return Math.max(0, worked() - productive); }
    }

    /** Periods by the local date their work period started; a shift past midnight stays on its first day. */
    final TreeMap<LocalDate, List<Period>> days = new TreeMap<>();
    /** Days changed since the last save, so storage rewrites only those. */
    final Set<LocalDate> dirty = new TreeSet<>();
    final TreeMap<Integer, Job> jobs = new TreeMap<>();
    boolean jobsDirty;
    /** The job the tracker's toggles act on; saved with the next change rather than when it is picked. */
    int selected = FIRST_JOB;

    WorkLog() { jobs.put(FIRST_JOB, new Job("Main job", false)); }

    // ---- Jobs ----

    String jobName(int job) {
        Job found = jobs.get(job);
        return found == null ? "Job " + job : found.name;
    }

    /** Jobs shown in the tracker, in the order they were added. */
    List<Integer> activeJobs() {
        List<Integer> active = new ArrayList<>();
        for (var entry : jobs.entrySet()) if (!entry.getValue().archived) active.add(entry.getKey());
        return active;
    }

    int addJob(String name) {
        int id = jobs.lastKey() + 1;
        jobs.put(id, new Job(checkName(name), false));
        jobsDirty = true;
        return id;
    }

    void renameJob(int job, String name) {
        jobs.get(job).name = checkName(name);
        jobsDirty = true;
    }

    boolean used(int job) {
        for (List<Period> periods : days.values()) {
            for (Period period : periods) if (period.kind == Kind.WORK && period.job == job) return true;
        }
        return false;
    }

    /** Deletes a job with no recorded work; a job with work is archived instead, so its history keeps its name. */
    void removeJob(int job) {
        if (activeJobs().size() <= 1 && !jobs.get(job).archived) throw new IllegalArgumentException("Keep at least one job");
        if (working(job)) throw new IllegalArgumentException("Punch out of this job first");
        if (used(job)) jobs.get(job).archived = true; else jobs.remove(job);
        if (selected == job) selected = activeJobs().get(0);
        jobsDirty = true;
    }

    void restoreJob(int job) {
        jobs.get(job).archived = false;
        jobsDirty = true;
    }

    private static String checkName(String name) {
        String trimmed = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Enter a job name");
        if (trimmed.length() > 60) throw new IllegalArgumentException("Use 60 characters or fewer");
        return trimmed;
    }

    /**
     * A few capitals for a job's name: the first letter of each word ("United Postal Service" is UPS), up to four;
     * a single word of four letters or fewer is kept whole, a longer one gives its first three.
     */
    static String shortName(String name) {
        List<String> words = new ArrayList<>();
        for (String word : name.split("[^\\p{L}\\p{N}]+")) if (!word.isEmpty()) words.add(word);
        if (words.isEmpty()) return "?";
        if (words.size() == 1) {
            String word = words.get(0);
            int letters = word.codePointCount(0, word.length());
            return (letters <= 4 ? word : word.substring(0, word.offsetByCodePoints(0, 3))).toUpperCase(Locale.ROOT);
        }
        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < words.size() && i < 4; i++) initials.appendCodePoint(words.get(i).codePointAt(0));
        return initials.toString().toUpperCase(Locale.ROOT);
    }

    /** One job per line: id, archived flag (0 or 1) and name, separated by '|'. */
    String jobsText() {
        StringBuilder text = new StringBuilder();
        for (var entry : jobs.entrySet()) {
            if (text.length() > 0) text.append('\n');
            text.append(entry.getKey()).append('|').append(entry.getValue().archived ? 1 : 0).append('|')
                    .append(entry.getValue().name);
        }
        return text.toString();
    }

    void readJobs(String text) {
        if (text == null || text.isEmpty()) return;
        jobs.clear();
        for (String line : text.split("\n")) {
            String[] parts = line.split("\\|", 3);
            jobs.put(Integer.parseInt(parts[0]), new Job(parts[2], parts[1].equals("1")));
        }
        if (jobs.isEmpty()) jobs.put(FIRST_JOB, new Job("Main job", false));
        if (!jobs.containsKey(selected) || jobs.get(selected).archived) selected = activeJobs().isEmpty() ? jobs.firstKey() : activeJobs().get(0);
    }

    // ---- Punch clock ----
    // Each job has its own clock: several jobs can be punched in, and productive at once. Nothing starts or stops
    // another toggle by itself, except that punching out of a job ends that job's productive or break time.

    /** Running work by job, found once and kept until a period is added, removed or ended. */
    private Map<Integer, Map.Entry<LocalDate, Period>> openWork;

    /** The running work period of a job and the day it belongs to, or null. */
    Map.Entry<LocalDate, Period> openWork(int job) {
        if (openWork == null) {
            openWork = new java.util.HashMap<>();
            for (var day : days.entrySet()) {
                for (Period period : day.getValue()) {
                    if (period.kind == Kind.WORK && period.open()) {
                        openWork.put(period.job, new AbstractMap.SimpleImmutableEntry<>(day.getKey(), period));
                    }
                }
            }
        }
        return openWork.get(job);
    }

    boolean working(int job) { return openWork(job) != null; }

    /** Whether any job is punched in. */
    boolean working() {
        for (int job : jobs.keySet()) if (working(job)) return true;
        return false;
    }

    /** Jobs punched in right now. */
    List<Integer> workingJobs() {
        List<Integer> working = new ArrayList<>();
        for (int job : jobs.keySet()) if (working(job)) working.add(job);
        return working;
    }

    boolean running(Kind kind, int job) {
        var work = openWork(job);
        if (work == null) return false;
        for (Period period : days.get(work.getKey())) {
            if (period.kind == kind && period.job == job && period.open()) return true;
        }
        return false;
    }

    /** Whether productive or break time can start for this job now: punched in, and the other one not running. */
    boolean canStart(Kind kind, int job) {
        if (kind == Kind.WORK) return !working(job);
        Kind other = kind == Kind.PRODUCTIVE ? Kind.BREAK : Kind.PRODUCTIVE;
        return working(job) && !running(other, job);
    }

    /** Punches in to a job; refused when that job already has work recorded at this moment. */
    void punchIn(int job, long now, ZoneId zone) {
        if (working(job)) return;
        LocalDate day = date(now, zone);
        if (overlap(day, job, now, now + 1, null, now) != null) {
            throw new IllegalArgumentException(jobName(job) + " already has work recorded at this time; change it in Details");
        }
        selected = job;
        add(day, new Period(Kind.WORK, now, OPEN, job));
    }

    /** Ends a job's running work period, and its productive or break time, which cannot outlast it. */
    void punchOut(int job, long now) {
        var work = openWork(job);
        if (work == null) return;
        for (Period period : days.get(work.getKey())) {
            if (period.job == job && period.open()) period.end = Math.max(period.start, now);
        }
        dirty.add(work.getKey());
        openWork = null;
    }

    /** Starts productive or break time for a job; refused unless punched in and the other one is stopped. */
    void start(Kind kind, int job, long now) {
        if (kind == Kind.WORK || running(kind, job)) return;
        if (!working(job)) throw new IllegalArgumentException("Punch in to " + jobName(job) + " first");
        if (!canStart(kind, job)) {
            throw new IllegalArgumentException(kind == Kind.PRODUCTIVE ? "Stop the lunch/break first" : "Stop productive time first");
        }
        var work = openWork(job);
        add(work.getKey(), new Period(kind, Math.max(now, work.getValue().start), OPEN, job));
    }

    void stop(Kind kind, int job, long now) {
        var work = openWork(job);
        if (work == null) return;
        for (Period period : days.get(work.getKey())) {
            if (period.kind == kind && period.job == job && period.open()) {
                period.end = Math.max(period.start, now);
                dirty.add(work.getKey());
            }
        }
    }

    // ---- Queries ----

    Totals totals(LocalDate day, long now) { return totals(day, now, ALL_JOBS); }

    /** Totals for one job, or summed over all jobs with ALL_JOBS; overlapping jobs each count their own time. */
    Totals totals(LocalDate day, long now, int job) {
        Totals totals = new Totals();
        for (Period period : days.getOrDefault(day, Collections.emptyList())) {
            if (job != ALL_JOBS && period.job != job) continue;
            long length = period.length(now);
            switch (period.kind) {
                case WORK -> totals.punched += length;
                case BREAK -> totals.breaks += length;
                case PRODUCTIVE -> totals.productive += length;
            }
        }
        return totals;
    }

    /** Jobs with work on this day, in job order. */
    List<Integer> jobsOn(LocalDate day) {
        TreeSet<Integer> on = new TreeSet<>();
        for (Period period : work(day)) on.add(period.job);
        return new ArrayList<>(on);
    }

    List<Period> periods(LocalDate day) { return days.getOrDefault(day, Collections.emptyList()); }

    List<Period> work(LocalDate day) {
        List<Period> work = new ArrayList<>();
        for (Period period : periods(day)) if (period.kind == Kind.WORK) work.add(period);
        return work;
    }

    /** A job's work periods on a day, in time order. */
    List<Period> work(LocalDate day, int job) {
        List<Period> work = new ArrayList<>();
        for (Period period : periods(day)) if (period.kind == Kind.WORK && period.job == job) work.add(period);
        return work;
    }

    /** The work period containing a productive or break period: its job's last one starting at or before it. */
    Period parent(LocalDate day, Period child) {
        Period parent = null;
        for (Period work : work(day, child.job)) if (work.start <= child.start) parent = work;
        return parent;
    }

    /** Productive and break periods inside a work period, in time order. */
    List<Period> children(LocalDate day, Period work) {
        List<Period> children = new ArrayList<>();
        for (Period period : periods(day)) {
            if (period.kind != Kind.WORK && period.job == work.job && parent(day, period) == work) children.add(period);
        }
        return children;
    }

    // ---- Editing ----

    /** Rounds to the nearest five-minute step. */
    static long snap(long time) { return Math.floorDiv(time + STEP / 2, STEP) * STEP; }

    /**
     * The nearest allowed value for one edge of a period. Within a job, work periods never overlap and always
     * contain their productive and break periods, which never overlap each other. Different jobs may overlap.
     * A running end is not editable.
     */
    long clamp(LocalDate day, Period period, boolean start, long to, long now, ZoneId zone) {
        long low, high;
        if (period.kind == Kind.WORK) {
            // Neighbours include the job's work on the days either side, such as a shift past midnight.
            Period before = null, after = null;
            for (Period other : workAround(day, period.job)) {
                if (other == period) continue;
                if (other.start < period.start) before = other; else if (after == null) after = other;
            }
            List<Period> children = children(day, period);
            if (start) {
                low = Math.max(before == null ? Long.MIN_VALUE : before.end(now), dayStart(day, zone));
                high = Math.min(period.end(now) - MIN_LENGTH, dayStart(day.plusDays(1), zone) - 1);
                if (!children.isEmpty()) high = Math.min(high, children.get(0).start);
            } else {
                if (period.open()) return period.end;
                low = period.start + MIN_LENGTH;
                for (Period child : children) low = Math.max(low, child.end(now));
                high = Math.min(after == null ? Long.MAX_VALUE : after.start, period.start + DAY);
            }
        } else {
            Period work = parent(day, period);
            if (work == null) return start ? period.start : period.end; // Damaged data: leave it as it is.
            List<Period> siblings = children(day, work);
            int index = siblings.indexOf(period);
            Period before = index > 0 ? siblings.get(index - 1) : null;
            Period after = index + 1 < siblings.size() ? siblings.get(index + 1) : null;
            if (start) {
                low = Math.max(work.start, before == null ? Long.MIN_VALUE : before.end(now));
                high = period.end(now) - MIN_LENGTH;
            } else {
                if (period.open()) return period.end;
                low = period.start + MIN_LENGTH;
                high = Math.min(work.end(now), after == null ? Long.MAX_VALUE : after.start);
            }
        }
        return high < low ? (start ? period.start : period.end) : Math.max(low, Math.min(high, to));
    }

    /** Moves one edge to the nearest allowed value and returns it. */
    long move(LocalDate day, Period period, boolean start, long to, long now, ZoneId zone) {
        long value = clamp(day, period, start, to, now, zone);
        if (start) period.start = value; else period.end = value;
        days.get(day).sort(ORDER);
        dirty.add(day);
        openWork = null;
        return value;
    }

    /** Removes a period; removing a work period also removes what it contains. */
    void remove(LocalDate day, Period period) {
        List<Period> periods = days.get(day);
        if (periods == null) return;
        if (period.kind == Kind.WORK) periods.removeAll(children(day, period));
        periods.remove(period);
        if (periods.isEmpty()) days.remove(day);
        dirty.add(day);
        openWork = null;
    }

    /**
     * The period of the same kind and job whose edge meets this one's start or end, so the two can merge;
     * productive and break time must also share their work period. Null if none meets it.
     */
    Period touching(LocalDate day, Period period, boolean start) {
        if (!start && period.open()) return null;
        long edge = start ? period.start : period.end;
        Period parent = period.kind == Kind.WORK ? null : parent(day, period);
        for (Period other : periods(day)) {
            if (other == period || other.kind != period.kind || other.job != period.job) continue;
            boolean meets = start ? !other.open() && other.end == edge : other.start == edge;
            if (meets && (period.kind == Kind.WORK || parent(day, other) == parent)) return other;
        }
        return null;
    }

    /** Joins two touching periods into the earlier one; merged work holds what both held. */
    Period merge(LocalDate day, Period a, Period b) {
        Period first = a.start <= b.start ? a : b, second = first == a ? b : a;
        List<Period> periods = days.get(day);
        if (periods == null || !periods.contains(first) || !periods.contains(second) || first.kind != second.kind
                || first.job != second.job || first.open() || first.end != second.start
                || (first.kind != Kind.WORK && parent(day, first) != parent(day, second))) {
            throw new IllegalArgumentException("Only touching periods of the same kind can merge");
        }
        first.end = second.end;
        periods.remove(second);
        dirty.add(day);
        openWork = null;
        return first;
    }

    /**
     * Cuts a period in two at a time inside it and returns the later part. Cutting work also cuts the productive
     * or break time it crosses, so each part keeps what lies inside it.
     */
    Period split(LocalDate day, Period period, long at, long now) {
        if (at - period.start < MIN_LENGTH || period.end(now) - at < MIN_LENGTH) {
            throw new IllegalArgumentException("Cut at least a minute from either end");
        }
        List<Period> pieces = new ArrayList<>(Collections.singletonList(period));
        if (period.kind == Kind.WORK) {
            for (Period child : children(day, period)) if (child.start < at && at < child.end(now)) pieces.add(child);
        }
        Period later = null;
        for (Period piece : pieces) {
            Period rest = new Period(piece.kind, at, piece.end, piece.job);
            piece.end = at;
            add(day, rest);
            if (piece == period) later = rest;
        }
        return later;
    }

    /** Moves a work period, with what it contains, to another job; refused if it would overlap that job's work. */
    void setJob(LocalDate day, Period work, int job, long now) {
        if (work.kind != Kind.WORK || work.job == job || !jobs.containsKey(job)) return;
        if (work.open() && working(job)) throw new IllegalArgumentException(jobName(job) + " is already punched in");
        if (overlap(day, job, work.start, work.end(now), work, now) != null) {
            throw new IllegalArgumentException(jobName(job) + " already has work at this time");
        }
        for (Period child : children(day, work)) child.job = job;
        work.job = job;
        dirty.add(day);
        openWork = null;
    }

    /** Adds a finished work period for a job: 9:00–17:00 if it has none that day, else an hour after its last one. */
    Period addWork(LocalDate day, long now, ZoneId zone, int job) {
        if (working(job)) throw new IllegalArgumentException("Punch out of " + jobName(job) + " before adding more work");
        List<Period> work = work(day, job);
        long start, end;
        if (work.isEmpty()) {
            start = dayStart(day, zone) + 9 * 3600;
            end = dayStart(day, zone) + 17 * 3600;
        } else {
            start = work.get(work.size() - 1).end;
            end = start + 3600;
        }
        // Step past any of the job's work it would overlap, including a shift from the day before.
        for (Period other = overlap(day, job, start, end, null, now); other != null; other = overlap(day, job, start, end, null, now)) {
            start = other.end(now);
            end = start + 3600;
        }
        if (start >= dayStart(day.plusDays(1), zone)) throw new IllegalArgumentException("This day has no room for another work period");
        Period period = new Period(Kind.WORK, start, end, job);
        add(day, period);
        return period;
    }

    /** Adds up to an hour of productive or break time in the largest free gap of a work period. */
    Period addInside(LocalDate day, Period work, Kind kind, long now) {
        long gapStart = 0, gapLength = 0, cursor = work.start;
        List<Period> children = children(day, work);
        List<Long> edges = new ArrayList<>();
        for (Period child : children) { edges.add(child.start); edges.add(child.end(now)); }
        edges.add(work.end(now)); edges.add(work.end(now));
        for (int i = 0; i < edges.size(); i += 2) {
            long length = edges.get(i) - cursor;
            if (length > gapLength) { gapLength = length; gapStart = cursor; }
            cursor = Math.max(cursor, edges.get(i + 1));
        }
        if (gapLength < MIN_LENGTH) throw new IllegalArgumentException("This work period has no free time left");
        Period period = new Period(kind, gapStart, gapStart + Math.min(gapLength, 3600), work.job);
        add(day, period);
        return period;
    }

    // ---- Storage form ----

    /**
     * One day as text: kind letter, start and end seconds, and "@job" on periods of jobs other than the first;
     * e.g. "W1730000000-1730028800@2,P1730001000-1730004600".
     */
    static String text(List<Period> periods) {
        StringBuilder text = new StringBuilder();
        for (Period period : periods) {
            if (text.length() > 0) text.append(',');
            text.append(period.kind.name().charAt(0)).append(period.start).append('-').append(period.end);
            if (period.job != FIRST_JOB) text.append('@').append(period.job);
        }
        return text.toString();
    }

    void read(LocalDate day, String text) {
        List<Period> periods = new ArrayList<>();
        for (String part : text.split(",")) {
            if (part.isEmpty()) continue;
            Kind kind = switch (part.charAt(0)) {
                case 'W' -> Kind.WORK;
                case 'P' -> Kind.PRODUCTIVE;
                case 'B' -> Kind.BREAK;
                default -> throw new IllegalArgumentException("Unknown period: " + part);
            };
            int dash = part.indexOf('-', 1), at = part.indexOf('@');
            long end = Long.parseLong(part.substring(dash + 1, at < 0 ? part.length() : at));
            int job = at < 0 ? FIRST_JOB : Integer.parseInt(part.substring(at + 1));
            periods.add(new Period(kind, Long.parseLong(part.substring(1, dash)), end, job));
        }
        periods.sort(ORDER);
        if (!periods.isEmpty()) days.put(day, periods);
        openWork = null;
    }

    // ---- Helpers ----

    private static final Comparator<Period> ORDER = Comparator.comparingLong((Period p) -> p.start)
            .thenComparing(p -> p.kind != Kind.WORK);

    private void add(LocalDate day, Period period) {
        List<Period> periods = days.computeIfAbsent(day, d -> new ArrayList<>());
        periods.add(period);
        periods.sort(ORDER);
        dirty.add(day);
        openWork = null;
    }

    /** A job's work from the day before to the day after, in time order, so limits see shifts past midnight. */
    private List<Period> workAround(LocalDate day, int job) {
        List<Period> work = new ArrayList<>();
        for (LocalDate date = day.minusDays(1); !date.isAfter(day.plusDays(1)); date = date.plusDays(1)) work.addAll(work(date, job));
        work.sort(ORDER);
        return work;
    }

    /** The job's work, other than {@code except}, that overlaps [start, end), or null. */
    private Period overlap(LocalDate day, int job, long start, long end, Period except, long now) {
        for (Period other : workAround(day, job)) {
            if (other != except && other.start < end && start < other.end(now)) return other;
        }
        return null;
    }

    static LocalDate date(long seconds, ZoneId zone) { return Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate(); }

    static long dayStart(LocalDate day, ZoneId zone) { return day.atStartOfDay(zone).toEpochSecond(); }
}
