package org.citriage.comparison;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.citriage.comparison.RunComparison.Failure;
import org.citriage.comparison.RunComparison.Status;
import org.citriage.log.FailureGroup;
import org.citriage.log.LogFinding;
import org.citriage.report.TestCaseResult;
import org.citriage.storage.SavedRun;

/** Ranks observed failures with concrete reasons and original evidence. */
public final class PriorityService {
    public List<Priority> prioritize(SavedRun current, List<Failure> failures) {
        TestObservations observations = new TestObservations(current);
        List<Priority> priorities = new ArrayList<>();
        for (Failure failure : failures) {
            Tier tier = failure.status() == Status.NEW_FAILURE ? Tier.CONFIRMED_NEW_FAILURE : Tier.OBSERVED_TEST_FAILURE;
            String reason = tier == Tier.CONFIRMED_NEW_FAILURE ? failure.reason()
                    : "The current JUnit execution reported " + failure.current().outcome() + ".";
            priorities.add(new TestPriority(tier, reason, failure.current()));
        }
        for (SavedRun.SavedLog log : current.logs()) {
            for (FailureGroup group : log.groups()) {
                priorities.add(groupPriority(observations, group));
            }
        }
        // The stable sort keeps source and observation order within each tier.
        priorities.sort(Comparator.comparing(Priority::tier));
        return List.copyOf(priorities);
    }

    private static GroupPriority groupPriority(TestObservations observations, FailureGroup group) {
        List<Member> members = group.members().stream().map(finding -> new Member(finding,
                finding.testIdentity().flatMap(observations::linkLogTest))).toList();
        Optional<LogFinding> blocker = group.members().stream().filter(PriorityService::blocking).findFirst();
        if (blocker.isPresent()) {
            return new GroupPriority(Tier.BLOCKING_BUILD_OR_SETUP,
                    "Stored rule " + blocker.get().ruleId() + " reports a build or dependency resolution failure.", group, members);
        }
        if (group.distinctKnownTestCount() > 1) {
            return new GroupPriority(Tier.MULTIPLE_KNOWN_TESTS,
                    "The same diagnostic was observed for " + group.distinctKnownTestCount() + " distinct named tests.",
                    group, members);
        }
        return new GroupPriority(Tier.OBSERVED_LOG_DIAGNOSTIC,
                "The log contains " + group.occurrenceCount() + " occurrence(s) of " + group.category() + ".", group, members);
    }

    private static boolean blocking(LogFinding finding) {
        return switch (finding.category()) {
            case COMPILATION_ERROR -> "java-compiler".equals(finding.ruleId()) || "maven-compilation".equals(finding.ruleId());
            case MISSING_DEPENDENCY -> "maven-dependency-resolution".equals(finding.ruleId());
            default -> false;
        };
    }

    public enum Tier {
        BLOCKING_BUILD_OR_SETUP, CONFIRMED_NEW_FAILURE, MULTIPLE_KNOWN_TESTS, OBSERVED_TEST_FAILURE, OBSERVED_LOG_DIAGNOSTIC
    }

    public sealed interface Priority permits TestPriority, GroupPriority {
        Tier tier();
        String reason();
    }

    public record TestPriority(Tier tier, String reason, TestCaseResult test) implements Priority {
    }

    public record GroupPriority(Tier tier, String reason, FailureGroup group, List<Member> members) implements Priority {
        public GroupPriority {
            members = List.copyOf(members);
        }
    }

    public record Member(LogFinding finding, Optional<TestCaseResult> junitTest) {
    }
}
