package com.fermapp.api.domain.fieldstatus;

import com.fermapp.api.common.util.Times;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The real state of a parcel, from its plan of work rather than from the last operation
 * (fieldStatus.ts). Days are counted in the server's local time zone, as in JavaScript.
 */
public final class FieldStatusRules {

    public enum FieldState { OVERDUE, DUE_TODAY, ACTIVE, SCHEDULED, COMPLETE }

    public static final Map<FieldState, String> LABELS = Map.of(
            FieldState.OVERDUE, "Overdue work",
            FieldState.ACTIVE, "Work in progress",
            FieldState.DUE_TODAY, "Work due today",
            FieldState.SCHEDULED, "Work scheduled",
            FieldState.COMPLETE, "All work completed");

    /** Work planned within this many days counts as the active period. */
    public static final int ACTIVE_PERIOD_DAYS = 7;

    /** A task as the status rules read it; {@code dueDate} is an ISO timestamp. */
    public record FieldTask(String id, String title, String type, String status, String dueDate, String parcelId) {
    }

    public record FieldStatus(FieldState state, String badge, int overdue, int dueToday, int upcoming, int pending, int later,
                              FieldTask nextDue, Integer completionScore, int done, int total) {
    }

    /** Which planned tasks an operation of each type carries out. */
    public static final Map<String, List<String>> WORK_TO_TASK_TYPES = Map.of(
            "PLOWING", List.of("SOIL_PREP"),
            "DISCING", List.of("SOIL_PREP"),
            "SUBSOILING", List.of("SOIL_PREP"),
            "SOWING", List.of("SOWING"),
            "FERTILIZING", List.of("FERTILIZATION"),
            "SPRAYING", List.of("HERBICIDE", "FUNGICIDE", "INSECTICIDE"),
            "HARVESTING", List.of("HARVEST"));

    /** A finished operation may close a task planned up to this many days later. */
    private static final int EARLY_WINDOW_DAYS = 3;

    private FieldStatusRules() {
    }

    private static long startOfLocalDay(Instant now) {
        LocalDate day = now.atZone(ZoneId.systemDefault()).toLocalDate();
        return day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private static long due(FieldTask task) {
        return Instant.parse(task.dueDate()).toEpochMilli();
    }

    public static FieldStatus fieldStatus(List<FieldTask> tasks, int activeWork, Instant now) {
        long today = startOfLocalDay(now);
        long tomorrow = today + Times.DAY_MS;
        long periodEnd = today + (ACTIVE_PERIOD_DAYS + 1) * Times.DAY_MS;
        int year = Times.localYear(now);

        int overdue = 0;
        int dueToday = 0;
        int dueTodayNotStarted = 0;
        int upcoming = 0;
        int later = 0;
        FieldTask nextDue = null;
        int done = 0;
        int total = 0;

        for (FieldTask task : tasks) {
            long due = due(task);
            if (Times.localYear(Instant.ofEpochMilli(due)) == year) {
                total += 1;
                if ("DONE".equals(task.status())) {
                    done += 1;
                }
            }
            if ("DONE".equals(task.status())) {
                continue;
            }
            if (due < today) {
                overdue += 1;
            } else if (due < tomorrow) {
                dueToday += 1;
                if ("TODO".equals(task.status())) {
                    dueTodayNotStarted += 1;
                }
            } else if (due < periodEnd) {
                upcoming += 1;
            } else {
                later += 1;
            }
            if (due >= today && (nextDue == null || due < due(nextDue))) {
                nextDue = task;
            }
        }

        boolean inProgress = tasks.stream().anyMatch(task -> "IN_PROGRESS".equals(task.status()) && due(task) < tomorrow);
        int pending = overdue + dueToday + upcoming;
        // Lateness needs attention first; then work happening now; then work due today that has not started.
        FieldState state = overdue > 0 ? FieldState.OVERDUE
                : activeWork > 0 || inProgress ? FieldState.ACTIVE
                : dueTodayNotStarted > 0 ? FieldState.DUE_TODAY
                : pending > 0 ? FieldState.SCHEDULED : FieldState.COMPLETE;

        boolean nextIsTomorrow = nextDue != null && due(nextDue) < tomorrow + Times.DAY_MS && due(nextDue) >= tomorrow;
        String badge = switch (state) {
            case OVERDUE -> plural(overdue, "task overdue", "tasks overdue");
            case ACTIVE -> pending > 0 ? "Work in progress, " + plural(pending, "task", "tasks") + " left" : "Work in progress";
            case DUE_TODAY -> plural(dueToday, "task due today", "tasks due today");
            case SCHEDULED -> pending == 1 && nextIsTomorrow ? "Work scheduled tomorrow"
                    : plural(pending, "scheduled activity", "scheduled activities");
            case COMPLETE -> "All work completed";
        };

        return new FieldStatus(state, badge, overdue, dueToday, upcoming, pending, later, nextDue,
                total > 0 ? (int) Math.round((double) done / total * 100) : null, done, total);
    }

    /** A task in the shape {@link #taskForFinishedWork} needs. */
    public interface OpenTask {
        String status();

        String type();

        Instant dueDate();
    }

    /**
     * The open task a finished operation carries out: same parcel, matching type,
     * due by a few days after the operation, the earliest first.
     */
    public static <T extends OpenTask> Optional<T> taskForFinishedWork(List<T> tasks, String workType, Instant finishedAt) {
        List<String> types = WORK_TO_TASK_TYPES.getOrDefault(workType, List.of());
        long limit = startOfLocalDay(finishedAt) + (EARLY_WINDOW_DAYS + 1) * Times.DAY_MS;
        return tasks.stream()
                .filter(task -> !"DONE".equals(task.status()) && types.contains(task.type()) && task.dueDate().toEpochMilli() < limit)
                .min(Comparator.comparing(OpenTask::dueDate));
    }
}
