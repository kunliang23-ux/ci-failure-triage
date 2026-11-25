package org.citriage.comparison;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.citriage.report.TestCaseResult;
import org.citriage.report.TestOutcome;
import org.citriage.storage.RunFacts;
import org.citriage.storage.RunMetadata;
import org.citriage.storage.RunMetadata.Field;
import org.citriage.storage.SavedRun;

/** Selects compatible prior executions and compares their stored JUnit observations. */
public final class RunComparison {
    private static final List<Field> COMPATIBILITY_FIELDS = List.of(Field.REPOSITORY, Field.WORKFLOW, Field.JOB,
            Field.ENVIRONMENT, Field.OS, Field.ARCHITECTURE, Field.TOOLCHAIN, Field.DEPENDENCY_FINGERPRINT,
            Field.TEST_COMMAND, Field.TEST_CONFIGURATION);

    /** The caller supplies either all other candidates or just the explicitly requested baseline. */
    public Selection select(SavedRun current, List<RunFacts> candidates) {
        List<EligibleBaseline> eligible = new ArrayList<>();
        List<Exclusion> excluded = new ArrayList<>();
        for (RunFacts candidate : candidates) {
            List<String> reasons = exclusions(current, candidate);
            if (reasons.isEmpty()) {
                eligible.add(new EligibleBaseline(candidate, candidate.metadata().instant(Field.COMPLETED_AT).orElseThrow()));
            } else {
                excluded.add(new Exclusion(candidate.id(), reasons));
            }
        }
        eligible.sort(Comparator.comparing(EligibleBaseline::completion).reversed()
                .thenComparing(baseline -> baseline.run().id()));
        Optional<EligibleBaseline> selected = eligible.stream().findFirst();
        for (int i = 1; i < eligible.size(); i++) {
            EligibleBaseline alternative = eligible.get(i);
            EligibleBaseline winner = eligible.get(0);
            String reason = alternative.completion().equals(winner.completion())
                    ? "Eligible with the same completion time. The selected run ID sorts first."
                    : "Eligible but completed earlier than the selected run.";
            excluded.add(new Exclusion(alternative.run().id(), List.of(reason)));
        }
        excluded.sort(Comparator.comparing(Exclusion::runId));
        return new Selection(selected, excluded);
    }

    private static List<String> exclusions(SavedRun current, RunFacts candidate) {
        if (candidate.id().equals(current.id())) {
            return List.of("The current run cannot be its own baseline.");
        }
        List<String> reasons = new ArrayList<>();
        RunMetadata metadata = candidate.metadata();
        Optional<String> conclusion = metadata.knownValue(Field.CONCLUSION);
        if (conclusion.isEmpty()) {
            reasons.add("Baseline CONCLUSION is unknown.");
        } else if (!RunMetadata.Conclusion.SUCCESS.name().equals(conclusion.get())) {
            reasons.add("Baseline CONCLUSION is " + conclusion.get() + ". Eligibility requires SUCCESS.");
        }
        Optional<Instant> currentStart = current.metadata().instant(Field.EXECUTED_AT);
        Optional<Instant> baselineStart = metadata.instant(Field.EXECUTED_AT);
        Optional<Instant> completion = metadata.instant(Field.COMPLETED_AT);
        if (currentStart.isEmpty()) {
            reasons.add("Current EXECUTED_AT is missing or invalid.");
        }
        if (completion.isEmpty()) {
            reasons.add("Baseline COMPLETED_AT is missing or invalid.");
        } else {
            if (currentStart.isPresent() && !completion.get().isBefore(currentStart.get())) {
                reasons.add("Baseline completion must precede the current execution start.");
            }
            if (baselineStart.filter(start -> start.isAfter(completion.get())).isPresent()) {
                reasons.add("Baseline completion precedes its own execution start.");
            }
        }
        if (metadata.knownValue(Field.EXECUTED_AT).isPresent() && baselineStart.isEmpty()) {
            reasons.add("Baseline EXECUTED_AT is invalid.");
        }
        if (candidate.junitExecutions() == 0) {
            reasons.add("Baseline has no JUnit executions.");
        } else if (candidate.passedExecutions() == 0) {
            reasons.add("Baseline has no passed JUnit executions.");
        }
        if (candidate.failedOrErroredExecutions() > 0) {
            reasons.add("Baseline has " + candidate.failedOrErroredExecutions() + " failed or errored JUnit executions.");
        }
        if (candidate.logFindings() > 0) {
            reasons.add("Baseline has " + candidate.logFindings() + " contradictory log failure findings.");
        }
        for (Field field : COMPATIBILITY_FIELDS) {
            Optional<String> now = current.metadata().knownValue(field);
            Optional<String> then = metadata.knownValue(field);
            if (now.isEmpty() || then.isEmpty()) {
                String scope = now.isEmpty() && then.isEmpty() ? "current and baseline" : now.isEmpty() ? "current" : "baseline";
                reasons.add("Unknown " + field.name() + " in " + scope + " metadata.");
            } else if (!now.equals(then)) {
                reasons.add("Different " + field.name() + " in current and baseline metadata.");
            }
        }
        return reasons;
    }

    public TestComparison compareTests(SavedRun current, Optional<SavedRun> baseline) {
        TestObservations now = new TestObservations(current);
        if (baseline.isEmpty()) {
            return new TestComparison(now.failures().stream().map(test -> new Failure(test, Status.UNAVAILABLE,
                    Optional.empty(), "A compatible baseline is unavailable.")).toList(), List.of());
        }
        TestObservations before = new TestObservations(baseline.get());
        List<Failure> failures = now.failures().stream().map(test -> compare(test, now, before)).toList();
        List<TestCaseResult> missing = before.executions().stream()
                .filter(test -> TestObservations.knownClass(test.identity()).isPresent()
                        && before.possibleMatches(test).size() == 1 && now.possibleMatches(test).isEmpty())
                .toList();
        return new TestComparison(failures, missing);
    }

    private static Failure compare(TestCaseResult test, TestObservations now, TestObservations before) {
        if (TestObservations.knownClass(test.identity()).isEmpty()) {
            return new Failure(test, Status.AMBIGUOUS, Optional.empty(), "The current test has no reported class name.");
        }
        if (now.possibleMatches(test).size() > 1) {
            return new Failure(test, Status.AMBIGUOUS, Optional.empty(), "Multiple current executions could share this identity.");
        }
        List<TestCaseResult> matches = before.possibleMatches(test);
        if (matches.isEmpty()) {
            return new Failure(test, Status.UNSEEN_TEST_FAILURE, Optional.empty(),
                    "The baseline has no observation of this reported identity.");
        }
        TestCaseResult prior = matches.get(0);
        if (matches.size() > 1 || TestObservations.knownClass(prior.identity()).isEmpty()
                || now.possibleMatches(prior).size() != 1 || before.possibleMatches(prior).size() != 1) {
            return new Failure(test, Status.AMBIGUOUS, Optional.empty(),
                    "Incomplete or repeated identities prevent a unique match between the runs.");
        }
        if (prior.outcome() == TestOutcome.PASSED) {
            return new Failure(test, Status.NEW_FAILURE, Optional.of(prior),
                    "The same reported test passed in the selected baseline and now reports " + test.outcome() + ".");
        }
        return new Failure(test, Status.NO_PRIOR_PASS, Optional.of(prior),
                "The matching baseline execution reported " + prior.outcome() + ".");
    }

    public enum Status {
        NEW_FAILURE, UNSEEN_TEST_FAILURE, NO_PRIOR_PASS, AMBIGUOUS, UNAVAILABLE
    }

    public record EligibleBaseline(RunFacts run, Instant completion) {
    }

    public record Exclusion(String runId, List<String> reasons) {
        public Exclusion {
            reasons = List.copyOf(reasons);
        }
    }

    public record Selection(Optional<EligibleBaseline> baseline, List<Exclusion> exclusions) {
        public Selection {
            exclusions = List.copyOf(exclusions);
        }
    }

    public record Failure(TestCaseResult current, Status status, Optional<TestCaseResult> baseline, String reason) {
    }

    public record TestComparison(List<Failure> failures, List<TestCaseResult> notObservedNow) {
        public TestComparison {
            failures = List.copyOf(failures);
            notObservedNow = List.copyOf(notObservedNow);
        }
    }
}
