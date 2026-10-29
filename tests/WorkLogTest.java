package style.spacedragon.repeatremind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Punch clock, editing rules, storage text and CSV with a fixed clock; plain JDK, no Android. */
public final class WorkLogTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final int J = WorkLog.FIRST_JOB;
    private static int assertions;

    public static void main(String[] args) {
        punchClock();
        totals();
        editing();
        adding();
        overnight();
        storage();
        csv();
        jobs();
        reviewFixes();
        shortNames();
        mergeAndSplit();
        System.out.println("PASS: " + assertions + " work log assertions");
    }

    private static void punchClock() {
        WorkLog log = new WorkLog();
        check(!log.working() && !log.running(WorkLog.Kind.PRODUCTIVE, J), "Starts stopped");
        check(log.canStart(WorkLog.Kind.WORK, J) && !log.canStart(WorkLog.Kind.PRODUCTIVE, J)
                && !log.canStart(WorkLog.Kind.BREAK, J), "Only punch in is available");
        try { log.start(WorkLog.Kind.PRODUCTIVE, J, at("08:55")); throw new AssertionError("Started productive without punching in"); }
        catch (IllegalArgumentException expected) { assertions++; }
        check(!log.working(), "Starting productive never punches in for you");
        log.punchIn(J, at("09:00"), UTC);
        log.punchIn(J, at("09:05"), UTC);
        check(log.working(J) && log.work(DAY).size() == 1, "Punching in twice keeps one work period");
        log.start(WorkLog.Kind.PRODUCTIVE, J, at("09:10"));
        check(log.running(WorkLog.Kind.PRODUCTIVE, J) && !log.canStart(WorkLog.Kind.BREAK, J), "Productive running; break unavailable");
        try { log.start(WorkLog.Kind.BREAK, J, at("10:00")); throw new AssertionError("A break stopped productive time"); }
        catch (IllegalArgumentException expected) { assertions++; }
        check(log.running(WorkLog.Kind.PRODUCTIVE, J) && !log.running(WorkLog.Kind.BREAK, J), "Nothing toggled for you");
        log.stop(WorkLog.Kind.PRODUCTIVE, J, at("10:00"));
        log.start(WorkLog.Kind.BREAK, J, at("10:00"));
        check(log.running(WorkLog.Kind.BREAK, J) && !log.canStart(WorkLog.Kind.PRODUCTIVE, J), "Break running; productive unavailable");
        log.stop(WorkLog.Kind.BREAK, J, at("10:30"));
        check(!log.running(WorkLog.Kind.BREAK, J) && log.working(J), "Stopping a break keeps working");
        log.start(WorkLog.Kind.BREAK, J, at("12:00"));
        log.punchOut(J, at("12:30"));
        check(!log.working() && !log.running(WorkLog.Kind.BREAK, J), "Punch out ends that job's break");
        check(log.periods(DAY).stream().noneMatch(WorkLog.Period::open), "No period left running");
        check(log.dirty.contains(DAY), "Changed day is marked for saving");
        log.punchOut(J, at("13:00"));
        check(log.work(DAY).get(0).end == at("12:30"), "Punching out twice changes nothing");
    }

    private static void totals() {
        WorkLog log = new WorkLog();
        log.punchIn(J, at("09:00"), UTC);
        log.start(WorkLog.Kind.PRODUCTIVE, J, at("09:00"));
        log.stop(WorkLog.Kind.PRODUCTIVE, J, at("12:00"));
        log.start(WorkLog.Kind.BREAK, J, at("12:00"));
        log.stop(WorkLog.Kind.BREAK, J, at("12:30"));
        WorkLog.Totals open = log.totals(DAY, at("13:00"));
        check(open.punched == hours(4) && open.breaks == minutes(30) && open.productive == hours(3)
                && open.worked() == minutes(210) && open.unproductive() == minutes(30), "Running totals count up to now");
        log.punchOut(J, at("17:00"));
        WorkLog.Totals day = log.totals(DAY, at("20:00"));
        check(day.punched == hours(8) && day.worked() == minutes(450) && day.unproductive() == minutes(270),
                "Closed totals stop at punch out");
        check(log.totals(DAY.plusDays(1), at("20:00")).punched == 0, "Empty day");
    }

    private static void editing() {
        check(WorkLog.snap(at("09:02")) == at("09:00") && WorkLog.snap(at("09:03")) == at("09:05"), "Snaps to five minutes");
        WorkLog log = sample();
        WorkLog.Period work = log.work(DAY).get(0);
        WorkLog.Period productive = log.children(DAY, work).get(0);
        WorkLog.Period lunch = log.children(DAY, work).get(1);
        // Work cannot shrink past what it contains, or move into the previous day.
        check(log.clamp(DAY, work, true, at("11:00"), 0, UTC) == at("09:30"), "Work start stops at its first child");
        check(log.clamp(DAY, work, true, LocalDate.of(2026, 10, 27).atStartOfDay(UTC).toEpochSecond(), 0, UTC)
                == DAY.atStartOfDay(UTC).toEpochSecond(), "Work start stays on its day");
        check(log.clamp(DAY, work, false, at("12:00"), 0, UTC) == at("13:00"), "Work end stops at its last child");
        check(log.clamp(DAY, work, false, at("18:00"), 0, UTC) == at("18:00"), "Work end can grow");
        // Children stay inside work and never overlap.
        check(log.clamp(DAY, productive, true, at("08:00"), 0, UTC) == at("09:00"), "Productive stays inside work");
        check(log.clamp(DAY, productive, false, at("12:45"), 0, UTC) == at("12:00"), "Productive cannot overlap lunch");
        check(log.clamp(DAY, lunch, true, at("11:00"), 0, UTC) == at("11:30"), "Lunch cannot overlap productive");
        check(log.clamp(DAY, lunch, false, at("17:30"), 0, UTC) == at("17:00"), "Lunch stays inside work");
        check(log.clamp(DAY, lunch, true, at("13:30"), 0, UTC) == at("12:59"), "Keeps at least a minute");
        log.dirty.clear();
        check(log.move(DAY, productive, false, at("11:00"), 0, UTC) == at("11:00") && productive.end == at("11:00")
                && log.dirty.contains(DAY), "Move applies and marks the day");
        // A second work period blocks the first from growing into it.
        WorkLog.Period evening = log.addWork(DAY, 0, UTC, J);
        check(evening.start == at("17:00") && evening.end == at("18:00"), "Next work starts where the last ended");
        check(log.clamp(DAY, work, false, at("19:00"), 0, UTC) == at("17:00"), "Work periods never overlap");
        check(log.clamp(DAY, evening, true, at("16:00"), 0, UTC) == at("17:00"), "Later work cannot move into earlier");
        // A running end is fixed; its start can move up to a minute before now.
        WorkLog running = new WorkLog();
        running.punchIn(J, at("09:00"), UTC);
        WorkLog.Period open = running.work(DAY).get(0);
        check(running.clamp(DAY, open, false, at("08:00"), at("10:00"), UTC) == WorkLog.OPEN, "Running end is fixed");
        check(running.clamp(DAY, open, true, at("11:00"), at("10:00"), UTC) == at("09:59"), "Running start stays before now");
        // Removing work removes its children.
        log.remove(DAY, work);
        check(log.work(DAY).size() == 1 && log.periods(DAY).size() == 1, "Removing work removes its children");
        log.remove(DAY, evening);
        check(!log.days.containsKey(DAY) && log.periods(DAY).isEmpty(), "An emptied day is dropped");
    }

    private static void adding() {
        WorkLog log = new WorkLog();
        WorkLog.Period work = log.addWork(DAY, 0, UTC, J);
        check(work.start == at("09:00") && work.end == at("17:00"), "First work period defaults to 9 to 5");
        WorkLog.Period productive = log.addInside(DAY, work, WorkLog.Kind.PRODUCTIVE, 0);
        check(productive.start == at("09:00") && productive.end == at("10:00"), "Child fills the largest gap, up to an hour");
        log.move(DAY, productive, false, at("16:30"), 0, UTC);
        WorkLog.Period lunch = log.addInside(DAY, work, WorkLog.Kind.BREAK, 0);
        check(lunch.start == at("16:30") && lunch.end == at("17:00"), "Child fits the remaining gap");
        try { log.addInside(DAY, work, WorkLog.Kind.BREAK, 0); throw new AssertionError("Added to a full period"); }
        catch (IllegalArgumentException expected) { assertions++; }
        WorkLog running = new WorkLog();
        running.punchIn(J, at("09:00"), UTC);
        try { running.addWork(DAY, at("10:00"), UTC, J); throw new AssertionError("Added work while punched in"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }

    private static void overnight() {
        WorkLog log = new WorkLog();
        log.punchIn(J, at("22:00"), UTC);
        log.start(WorkLog.Kind.PRODUCTIVE, J, Instant.parse("2026-10-29T01:00:00Z").getEpochSecond());
        log.punchOut(J, Instant.parse("2026-10-29T06:00:00Z").getEpochSecond());
        check(log.days.size() == 1 && log.periods(DAY).size() == 2, "A night shift stays on the day it started");
        check(log.totals(DAY, 0).punched == hours(8) && log.totals(DAY, 0).productive == hours(5), "Night shift totals");
    }

    private static void storage() {
        WorkLog log = sample();
        log.punchIn(J, at("18:00"), UTC);
        String text = WorkLog.text(log.periods(DAY));
        check(text.startsWith("W" + at("09:00") + "-" + at("17:00") + ",P") && text.endsWith("-0"), "Compact text, open end as 0");
        WorkLog copy = new WorkLog();
        copy.read(DAY, text);
        check(WorkLog.text(copy.periods(DAY)).equals(text) && copy.working(), "Text round-trips, including a running period");
        copy.read(DAY.plusDays(1), "");
        check(!copy.days.containsKey(DAY.plusDays(1)), "Empty text adds no day");
        try { copy.read(DAY, "X1-2"); throw new AssertionError("Accepted an unknown period"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }

    private static void csv() {
        WorkLog log = sample();
        log.days.put(DAY.plusDays(2), new java.util.ArrayList<>(List.of(
                new WorkLog.Period(WorkLog.Kind.WORK, Instant.parse("2026-10-30T22:00:00Z").getEpochSecond(), WorkLog.OPEN))));
        long now = Instant.parse("2026-10-31T01:30:00Z").getEpochSecond();
        String[] rows = WorkCsv.daily(log, DAY, DAY.plusDays(2), WorkLog.ALL_JOBS, now, UTC).split("\r\n");
        check(rows.length == 3 && rows[0].equals(WorkCsv.DAILY_HEADER), "Header and one row per worked day");
        check(rows[1].equals("2026-10-28,Wednesday,Main job,2026-10-28 09:00,2026-10-28 17:00,8.00,1.00,7.00,2.00,5.00,29,7:00,1"),
                "Day row: " + rows[1]);
        check(rows[2].equals("2026-10-30,Friday,Main job,2026-10-30 22:00,,3.50,0.00,3.50,0.00,3.50,0,3:30,1"),
                "Running row: " + rows[2]);
        check(WorkCsv.daily(log, DAY.plusDays(5), DAY.plusDays(6), WorkLog.ALL_JOBS, now, UTC)
                .equals(WorkCsv.DAILY_HEADER + "\r\n"), "Empty range");
        check(WorkCsv.clock(minutes(450)).equals("7:30") && WorkCsv.clock(hours(26)).equals("26:00"), "Display clock");

        String[] periods = WorkCsv.periods(log, DAY, DAY.plusDays(2), WorkLog.ALL_JOBS, now, UTC).split("\r\n");
        check(periods.length == 5 && periods[0].equals(WorkCsv.PERIODS_HEADER), "One row per period");
        check(periods[1].equals("2026-10-28,Wednesday,Main job,Work,2026-10-28 09:00,2026-10-28 17:00,480,8.00"), periods[1]);
        check(periods[2].equals("2026-10-28,Wednesday,Main job,Productive,2026-10-28 09:30,2026-10-28 11:30,120,2.00"), periods[2]);
        check(periods[3].equals("2026-10-28,Wednesday,Main job,Break,2026-10-28 12:00,2026-10-28 13:00,60,1.00"), periods[3]);
        check(periods[4].equals("2026-10-30,Friday,Main job,Work,2026-10-30 22:00,,210,3.50"), periods[4]);

        check(WorkCsv.cell("Cafe").equals("Cafe") && WorkCsv.cell("Bar, grill").equals("\"Bar, grill\"")
                && WorkCsv.cell("Say \"hi\"").equals("\"Say \"\"hi\"\"\"") && WorkCsv.cell("=SUM(A1)").equals("'=SUM(A1)")
                && WorkCsv.cell("-shift").equals("'-shift"), "Text cells are quoted and formula-safe");
    }

    private static void jobs() {
        WorkLog log = new WorkLog();
        check(log.activeJobs().equals(List.of(J)) && log.jobName(J).equals("Main job"), "Starts with one job");
        int cafe = log.addJob("  Cafe,   evenings ");
        check(log.jobName(cafe).equals("Cafe, evenings") && log.jobsDirty, "Names are tidied; jobs marked for saving");
        try { log.addJob("  "); throw new AssertionError("Accepted a blank name"); }
        catch (IllegalArgumentException expected) { assertions++; }
        // Jobs run independently and at the same time; nothing is punched in or out for you.
        log.punchIn(cafe, at("09:00"), UTC);
        log.start(WorkLog.Kind.PRODUCTIVE, cafe, at("09:30"));
        log.punchIn(J, at("10:00"), UTC);
        check(log.working(cafe) && log.working(J) && log.running(WorkLog.Kind.PRODUCTIVE, cafe)
                && log.workingJobs().equals(List.of(J, cafe)), "Punching in to a second job leaves the first running");
        log.start(WorkLog.Kind.PRODUCTIVE, J, at("10:15"));
        check(log.running(WorkLog.Kind.PRODUCTIVE, J) && log.running(WorkLog.Kind.PRODUCTIVE, cafe), "Productive at both jobs");
        check(log.canStart(WorkLog.Kind.BREAK, cafe) == false && !log.canStart(WorkLog.Kind.BREAK, J), "Per-job break rule");
        log.stop(WorkLog.Kind.PRODUCTIVE, cafe, at("12:00"));
        log.start(WorkLog.Kind.BREAK, cafe, at("12:00"));
        check(log.running(WorkLog.Kind.PRODUCTIVE, J) && log.running(WorkLog.Kind.BREAK, cafe), "One job on break, the other productive");
        log.punchOut(cafe, at("13:00"));
        check(!log.working(cafe) && log.working(J) && log.running(WorkLog.Kind.PRODUCTIVE, J),
                "Punching out of one job leaves the other running");
        log.punchOut(J, at("17:00"));
        check(log.totals(DAY, 0, cafe).punched == hours(4) && log.totals(DAY, 0, cafe).productive == minutes(150)
                && log.totals(DAY, 0, cafe).breaks == hours(1), "Totals by job");
        check(log.totals(DAY, 0, J).punched == hours(7) && log.totals(DAY, 0, J).productive == minutes(405),
                "The other job's totals");
        check(log.totals(DAY, 0).punched == hours(11), "All-job totals add each job's own time");
        check(log.jobsOn(DAY).equals(List.of(J, cafe)), "Jobs worked on a day");
        // Overlapping work periods of different jobs keep their own children.
        WorkLog.Period cafeWork = log.work(DAY, cafe).get(0), mainWork = log.work(DAY, J).get(0);
        check(log.children(DAY, cafeWork).size() == 2 && log.children(DAY, mainWork).size() == 1, "Children stay with their job");
        // Editing limits apply within a job only.
        check(log.clamp(DAY, mainWork, true, at("08:00"), 0, UTC) == at("08:00"), "A job's work may overlap another job's");
        String[] rows = WorkCsv.daily(log, DAY, DAY, WorkLog.ALL_JOBS, 0, UTC).split("\r\n");
        check(rows.length == 3 && rows[1].contains(",Main job,") && rows[2].contains(",\"Cafe, evenings\","), "A row per job");
        check(WorkCsv.daily(log, DAY, DAY, cafe, 0, UTC).split("\r\n").length == 2, "Export filtered to one job");
        check(WorkCsv.periods(log, DAY, DAY, cafe, 0, UTC).split("\r\n").length == 4, "Periods filtered to one job");
        // Storage keeps every period's job; the first job needs no marker.
        String text = WorkLog.text(log.periods(DAY));
        check(text.contains("@" + cafe) && !text.contains("@" + J), "Job marker on periods");
        WorkLog copy = new WorkLog();
        copy.readJobs(log.jobsText());
        copy.read(DAY, text);
        check(copy.jobName(cafe).equals("Cafe, evenings") && copy.work(DAY, cafe).size() == 1
                && copy.children(DAY, copy.work(DAY, cafe).get(0)).size() == 2, "Jobs and markers round-trip");
        WorkLog legacy = new WorkLog();
        legacy.read(DAY, "W" + at("09:00") + "-" + at("17:00"));
        check(legacy.work(DAY).get(0).job == J, "Work saved before jobs belongs to the first job");
        // Moving work to another job is refused when it would overlap that job's work.
        try { log.setJob(DAY, cafeWork, J, 0); throw new AssertionError("Moved work onto overlapping work"); }
        catch (IllegalArgumentException expected) { assertions++; }
        WorkLog.Period evening = log.addWork(DAY, 0, UTC, J);
        log.setJob(DAY, evening, cafe, 0);
        check(evening.job == cafe, "Changing a work period's job");
        // A used job is archived, keeping its name; an unused one is deleted; the last active job stays.
        log.removeJob(cafe);
        check(log.jobs.get(cafe).archived && log.jobName(cafe).equals("Cafe, evenings") && !log.activeJobs().contains(cafe),
                "Used job is archived");
        int unused = log.addJob("Spare");
        log.removeJob(unused);
        check(!log.jobs.containsKey(unused), "Unused job is deleted");
        try { log.removeJob(J); throw new AssertionError("Removed the last active job"); }
        catch (IllegalArgumentException expected) { assertions++; }
        log.restoreJob(cafe);
        log.renameJob(cafe, "Cafe");
        check(log.activeJobs().contains(cafe) && log.jobName(cafe).equals("Cafe"), "Restore and rename");
        log.punchIn(cafe, at("18:00"), UTC);
        try { log.removeJob(cafe); throw new AssertionError("Removed a job that is punched in"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }

    private static void reviewFixes() {
        // Punching in inside work already recorded for that job is refused instead of double-counting.
        WorkLog log = new WorkLog();
        log.addWork(DAY, 0, UTC, J);
        try { log.punchIn(J, at("14:00"), UTC); throw new AssertionError("Punched in inside recorded work"); }
        catch (IllegalArgumentException expected) { assertions++; }
        log.punchIn(J, at("17:00"), UTC);
        check(log.working(J), "Punching in where recorded work ends is fine");
        int other = log.addJob("Other");
        log.punchIn(other, at("14:00"), UTC);
        check(log.working(other), "Another job can punch in during this job's recorded work");

        // A shift past midnight limits the next day's work for the same job.
        log = new WorkLog();
        LocalDate next = DAY.plusDays(1);
        log.punchIn(J, at("22:00"), UTC);
        long nextDay = next.atStartOfDay(UTC).toEpochSecond();
        try { log.addWork(next, nextDay + 3600, UTC, J); throw new AssertionError("Added work during a running shift"); }
        catch (IllegalArgumentException expected) { assertions++; }
        log.punchOut(J, nextDay + 10 * 3600); // 22:00 to 10:00 the next day
        WorkLog.Period added = log.addWork(next, nextDay + 12 * 3600, UTC, J);
        check(added.start == nextDay + 10 * 3600, "New work steps past a shift from the day before");
        check(log.clamp(next, added, true, nextDay + 3600, 0, UTC) == nextDay + 10 * 3600,
                "A start cannot move into the previous day's shift");

        // Moving running work to a job that is already punched in is refused.
        log = new WorkLog();
        int second = log.addJob("Second");
        log.punchIn(second, at("08:00"), UTC);
        log.punchIn(J, at("09:00"), UTC);
        try { log.setJob(DAY, log.work(DAY, J).get(0), second, at("10:00")); throw new AssertionError("Two running periods"); }
        catch (IllegalArgumentException expected) { assertions++; }

        // On a 23-hour day a work start cannot move onto the next date.
        ZoneId toronto = ZoneId.of("America/Toronto");
        LocalDate spring = LocalDate.of(2026, 3, 8);
        log = new WorkLog();
        WorkLog.Period late = log.addWork(spring, 0, toronto, J);
        late.end = spring.plusDays(1).atStartOfDay(toronto).toEpochSecond() + 2 * 3600;
        long limit = log.clamp(spring, late, true, late.end - 600, 0, toronto);
        check(WorkLog.date(limit, toronto).equals(spring), "Start stays on its own date across DST");

        // A period without a work period of its job (damaged data) is left alone rather than crashing.
        log = new WorkLog();
        log.read(DAY, "W" + at("09:00") + "-" + at("17:00") + "@2,P" + at("10:00") + "-" + at("11:00"));
        WorkLog.Period orphan = log.periods(DAY).get(1);
        check(log.clamp(DAY, orphan, true, at("09:30"), 0, UTC) == at("10:00"), "Orphaned period is not moved");

        // The running-work lookup follows changes.
        log = new WorkLog();
        check(!log.working(J), "Not working");
        log.punchIn(J, at("09:00"), UTC);
        check(log.working(J), "Lookup sees punch in");
        log.punchOut(J, at("10:00"));
        check(!log.working(J), "Lookup sees punch out");
        log.punchIn(J, at("11:00"), UTC);
        log.remove(DAY, log.work(DAY, J).get(1));
        check(!log.working(J), "Lookup sees removal");
    }

    /** 9:00–17:00 work with productive 9:30–11:30 and lunch 12:00–13:00. */
    private static WorkLog sample() {
        WorkLog log = new WorkLog();
        WorkLog.Period work = log.addWork(DAY, 0, UTC, J);
        WorkLog.Period productive = log.addInside(DAY, work, WorkLog.Kind.PRODUCTIVE, 0);
        productive.start = at("09:30"); productive.end = at("11:30");
        WorkLog.Period lunch = log.addInside(DAY, work, WorkLog.Kind.BREAK, 0);
        lunch.start = at("12:00"); lunch.end = at("13:00");
        log.days.get(DAY).sort(java.util.Comparator.comparingLong(p -> p.start));
        return log;
    }

    private static long at(String time) { return Instant.parse("2026-10-28T" + time + ":00Z").getEpochSecond(); }
    private static long hours(long h) { return h * 3600; }
    private static long minutes(long m) { return m * 60; }
    private static void shortNames() {
        check(WorkLog.shortName("United Postal Service").equals("UPS"), "Initials of each word");
        check(WorkLog.shortName("Main job").equals("MJ"), "Two words, two letters");
        check(WorkLog.shortName("Cafe, evenings").equals("CE"), "Punctuation separates words");
        check(WorkLog.shortName("Cafe").equals("CAFE") && WorkLog.shortName("Warehouse").equals("WAR"),
                "One short word is kept whole; a long one gives three letters");
        check(WorkLog.shortName("Bank of the West Coast").equals("BOTW"), "At most four letters");
        check(WorkLog.shortName("Store 42").equals("S4") && WorkLog.shortName("--").equals("?"), "Digits count; no letters gives ?");
    }

    private static void mergeAndSplit() {
        WorkLog log = sample();
        WorkLog.Period work = log.work(DAY).get(0);
        WorkLog.Period lunch = log.children(DAY, work).get(1);
        // Cutting work at 12:30 also cuts lunch, and each part keeps what lies inside it.
        WorkLog.Period afternoon = log.split(DAY, work, at("12:30"), 0);
        check(work.end == at("12:30") && afternoon.start == at("12:30") && afternoon.end == at("17:00"), "Cut work in two");
        check(lunch.end == at("12:30") && log.children(DAY, work).size() == 2, "Morning keeps productive and half of lunch");
        List<WorkLog.Period> later = log.children(DAY, afternoon);
        check(later.size() == 1 && later.get(0).kind == WorkLog.Kind.BREAK && later.get(0).start == at("12:30")
                && later.get(0).end == at("13:00"), "Afternoon gets the rest of lunch");
        check(log.dirty.contains(DAY) && log.totals(DAY, 0).worked() == 7 * 3600, "Cutting changes no totals");
        // Touching handles merge the parts back, lunch included once its work is whole again.
        check(log.touching(DAY, work, false) == afternoon && log.touching(DAY, afternoon, true) == work, "Work parts touch");
        check(log.touching(DAY, lunch, false) == null, "Lunch halves in different work do not merge");
        log.merge(DAY, afternoon, work);
        check(log.work(DAY).size() == 1 && work.end == at("17:00"), "Merged back into one work period");
        WorkLog.Period rest = log.touching(DAY, lunch, false);
        check(rest != null && log.merge(DAY, lunch, rest) == lunch && lunch.end == at("13:00")
                && log.children(DAY, work).size() == 2, "Lunch halves merge once in one work period");
        check(log.touching(DAY, log.children(DAY, work).get(0), false) == null, "Productive does not merge with lunch");
        // Bad cuts and merges are refused.
        try { log.split(DAY, work, at("09:00") + 30, 0); throw new AssertionError("Cut at the very start"); }
        catch (IllegalArgumentException expected) { assertions++; }
        WorkLog.Period evening = log.addWork(DAY, 0, UTC, J);
        evening.start = at("18:00"); evening.end = at("19:00");
        try { log.merge(DAY, work, evening); throw new AssertionError("Merged across a gap"); }
        catch (IllegalArgumentException expected) { assertions++; }
        // A running period cut in two keeps running in its later part.
        WorkLog running = new WorkLog();
        running.punchIn(J, at("09:00"), UTC);
        WorkLog.Period open = running.work(DAY).get(0);
        WorkLog.Period now = running.split(DAY, open, at("10:00"), at("11:00"));
        check(open.end == at("10:00") && now.open() && running.working(J) && running.openWork(J).getValue() == now,
                "The later part keeps running");
    }

    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
