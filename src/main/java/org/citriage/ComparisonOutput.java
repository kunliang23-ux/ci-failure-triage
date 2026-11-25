package org.citriage;

import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.citriage.comparison.PriorityService.GroupPriority;
import org.citriage.comparison.PriorityService.Member;
import org.citriage.comparison.PriorityService.Priority;
import org.citriage.comparison.PriorityService.TestPriority;
import org.citriage.comparison.RunComparison.Exclusion;
import org.citriage.comparison.RunComparison.Failure;
import org.citriage.comparison.RunComparison.Selection;
import org.citriage.comparison.RunComparison.TestComparison;
import org.citriage.log.FailureGroup;
import org.citriage.report.OutcomeDetail;
import org.citriage.report.TestCaseResult;

import static org.citriage.CiTriageCli.printEvidence;
import static org.citriage.CliDisplay.location;
import static org.citriage.CliDisplay.text;

final class ComparisonOutput {
    private ComparisonOutput() {
    }

    static void print(Report report, PrintStream out) {
        out.println("Current run: " + text(report.currentId()));
        out.println("Current execution start: " + report.currentStart().map(Instant::toString).orElse("unknown"));
        report.requestedBaseline().ifPresent(id -> out.println("Requested baseline: " + text(id)));
        var selected = report.selection().baseline();
        if (selected.isPresent()) {
            var baseline = selected.get();
            out.println("Comparison: available");
            out.println("Baseline: " + text(baseline.run().id()));
            out.println("Baseline completed: " + baseline.completion());
            out.println(report.requestedBaseline().isPresent()
                    ? "Selection: the requested run met all eligibility requirements."
                    : "Selection: latest eligible completion. Equal times are ordered by run ID.");
            out.println("Eligibility: successful completion before the current start, matching execution metadata, and "
                    + baseline.run().passedExecutions() + " passed JUnit executions.");
        } else {
            out.println("Comparison: unavailable");
            out.println(report.requestedBaseline().isPresent()
                    ? "The requested baseline is ineligible." : "No compatible earlier passing run was found.");
            if (report.selection().exclusions().isEmpty()) {
                out.println("Automatic selection had no other saved runs to evaluate.");
            }
        }
        out.println("Excluded baselines: " + report.selection().exclusions().size());
        for (Exclusion exclusion : report.selection().exclusions()) {
            out.println("  " + text(exclusion.runId()));
            exclusion.reasons().forEach(reason -> out.println("    - " + text(reason)));
        }

        out.println("Current JUnit failures: " + report.tests().failures().size());
        for (Failure failure : report.tests().failures()) {
            out.println("  " + failure.status() + " " + testName(failure.current()));
            out.println("    " + failure.reason());
            failure.baseline().ifPresent(prior -> out.println("    Baseline " + prior.outcome()
                    + " at XML diagnostic location " + location(prior.location())));
        }
        if (selected.isPresent()) {
            out.println("Not observed now: " + report.tests().notObservedNow().size());
            report.tests().notObservedNow().forEach(test -> out.println("  NOT_OBSERVED_NOW " + testName(test)));
        }

        out.println("Investigation priorities: " + report.priorities().size());
        report.priorities().forEach(priority -> printPriority(priority, out));
        out.println("Priorities describe saved observations. Establish cause with further evidence.");
    }

    private static void printPriority(Priority priority, PrintStream out) {
        if (priority instanceof TestPriority failure) {
            TestCaseResult test = failure.test();
            out.println(failure.tier() + " " + testName(test));
            out.println("  Reason: " + failure.reason());
            out.println("  XML diagnostic location: " + location(test.location()));
            for (OutcomeDetail detail : test.details()) {
                out.println("  " + detail.outcome() + ": " + detail.message().map(CliDisplay::text)
                        .orElse("(no message supplied)"));
                detail.type().ifPresent(type -> out.println("  Type: " + text(type)));
                out.println("  XML diagnostic location: " + location(detail.location()));
                if (!detail.text().isEmpty()) {
                    detail.text().lines().forEach(line -> out.println("    " + text(line)));
                }
            }
        } else if (priority instanceof GroupPriority diagnostic) {
            FailureGroup group = diagnostic.group();
            out.println(diagnostic.tier() + " " + group.id() + " | " + group.category());
            out.println("  Reason: " + text(diagnostic.reason()));
            out.printf("  Occurrences: %d | Distinct known tests: %d | Unknown test occurrences: %d%n",
                    group.occurrenceCount(), group.distinctKnownTestCount(), group.unknownTestOccurrenceCount());
            for (Member member : diagnostic.members()) {
                String name = member.finding().testIdentity().map(test -> text(test.displayName())).orElse("unknown");
                out.println("  Log test: " + name + " [" + text(member.finding().ruleId()) + "]");
                member.junitTest().ifPresent(test -> out.println("  Linked JUnit test: " + testName(test)
                        + " at XML diagnostic location " + location(test.location())));
                printEvidence(member.finding().evidence(), out);
            }
        }
    }

    private static String testName(TestCaseResult test) {
        String name = test.className().filter(value -> !value.isBlank())
                .map(className -> className + "#" + test.name()).orElse(test.name() + " (unknown class)");
        return text(name + " [suite " + String.join(" / ", test.suitePath().stream()
                .map(suite -> suite.isBlank() ? "unknown" : suite).toList()) + "]");
    }

    record Report(String currentId, Optional<Instant> currentStart, Optional<String> requestedBaseline,
                  Selection selection, TestComparison tests, List<Priority> priorities) {
        Report {
            priorities = List.copyOf(priorities);
        }
    }
}
