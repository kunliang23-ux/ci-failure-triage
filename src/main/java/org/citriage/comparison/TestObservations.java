package org.citriage.comparison;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.citriage.report.TestCaseResult;
import org.citriage.report.TestIdentity;
import org.citriage.report.TestOutcome;
import org.citriage.storage.SavedRun;

/** Indexes the actual executions, including repeated and partially identified observations. */
final class TestObservations {
    private final List<TestCaseResult> executions;
    private final Map<String, List<TestCaseResult>> byName = new HashMap<>();

    TestObservations(SavedRun run) {
        executions = run.reports().stream().flatMap(saved -> saved.report().testCases().stream()).toList();
        for (TestCaseResult test : executions) {
            byName.computeIfAbsent(test.name(), ignored -> new ArrayList<>()).add(test);
        }
    }

    List<TestCaseResult> executions() {
        return executions;
    }

    List<TestCaseResult> failures() {
        return executions.stream()
                .filter(test -> test.outcome() == TestOutcome.FAILED || test.outcome() == TestOutcome.ERROR).toList();
    }

    List<TestCaseResult> possibleMatches(TestCaseResult test) {
        Optional<String> className = knownClass(test.identity());
        return byName.getOrDefault(test.name(), List.of()).stream()
                .filter(candidate -> classesCouldMatch(className, knownClass(candidate.identity())))
                .filter(candidate -> !completeSuite(test) || !completeSuite(candidate)
                        || test.suitePath().equals(candidate.suitePath()))
                .toList();
    }

    Optional<TestCaseResult> linkLogTest(TestIdentity identity) {
        Optional<String> className = knownClass(identity);
        if (className.isEmpty()) {
            return Optional.empty();
        }
        List<TestCaseResult> possible = byName.getOrDefault(identity.testName(), List.of()).stream()
                .filter(test -> classesCouldMatch(className, knownClass(test.identity()))).toList();
        if (possible.size() == 1 && knownClass(possible.get(0).identity()).isPresent()) {
            return Optional.of(possible.get(0));
        }
        return Optional.empty();
    }

    static Optional<String> knownClass(TestIdentity identity) {
        return identity.className().filter(name -> !name.isBlank());
    }

    private static boolean classesCouldMatch(Optional<String> first, Optional<String> second) {
        return first.isEmpty() || second.isEmpty() || first.equals(second);
    }

    private static boolean completeSuite(TestCaseResult test) {
        return !test.suitePath().isEmpty() && test.suitePath().stream().noneMatch(String::isBlank);
    }
}
