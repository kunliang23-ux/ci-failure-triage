package com.kunliang.citriage.log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kunliang.citriage.report.ReportSource;

import static com.kunliang.citriage.log.LogDiagnosticText.STACK_FRAME;
import static com.kunliang.citriage.log.LogDiagnosticText.SUREFIRE_TEST;
import static com.kunliang.citriage.log.LogDiagnosticText.diagnosticText;
import static com.kunliang.citriage.log.LogDiagnosticText.matchingText;
import static com.kunliang.citriage.log.LogDiagnosticText.signatureText;

/** Groups classified findings from one log snapshot in source order. */
public final class LogGrouper {
    public List<FailureGroup> group(List<LogFinding> findings) {
        if (findings.isEmpty()) {
            return List.of();
        }
        ReportSource source = findings.get(0).evidence().source();
        List<LogFinding> ordered = findings.stream()
                .sorted(Comparator.comparingInt(finding -> finding.evidence().firstLine()))
                .toList();
        Map<GroupKey, List<LogFinding>> membersBySignature = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            LogFinding finding = ordered.get(i);
            if (finding.evidence().source() != source) {
                throw new IllegalArgumentException("Group findings from one log snapshot at a time.");
            }
            // Generic compiler summaries and unknowns carry too little detail for a match.
            boolean singleton = finding.category() == DiagnosticCategory.UNKNOWN
                    || "maven-compilation".equals(finding.ruleId());
            GroupKey key = new GroupKey(finding.category(), signature(finding), singleton ? i + 1 : 0);
            membersBySignature.computeIfAbsent(key, ignored -> new ArrayList<>()).add(finding);
        }

        List<FailureGroup> groups = new ArrayList<>();
        Map<String, Integer> idCounts = new HashMap<>();
        for (var entry : membersBySignature.entrySet()) {
            GroupKey key = entry.getKey();
            List<LogFinding> members = entry.getValue();
            String baseId = key.singletonPosition() == 0 ? diagnosticId(key)
                    : "line-" + members.get(0).evidence().firstLine();
            // Match by the full signature. A shortened display hash can be shared by distinct signatures.
            int sequence = idCounts.merge(baseId, 1, Integer::sum);
            String id = sequence == 1 ? baseId : baseId + "-" + sequence;
            groups.add(new FailureGroup(id, key.category(), key.signature(), members));
        }
        return List.copyOf(groups);
    }

    private static String signature(LogFinding finding) {
        List<LogLine> lines = finding.evidence().lines();
        int start = lines.size() > 1 && SUREFIRE_TEST.matcher(diagnosticText(matchingText(lines.get(0)))).matches()
                ? 1 : 0;
        List<String> diagnostics = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            LogLine line = lines.get(i);
            if (i > start && finding.category() == DiagnosticCategory.COMPILATION_ERROR) {
                // A javac source or caret line can encode a column through its indentation.
                diagnostics.add(line.text());
            } else if (i == start || !STACK_FRAME.matcher(diagnosticText(matchingText(line))).matches()) {
                diagnostics.add(signatureText(line));
            }
        }
        return String.join("\n", diagnostics);
    }

    private static String diagnosticId(GroupKey key) {
        byte[] text = (key.category().name() + "\n" + key.signature()).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text);
            return "g-" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime must provide SHA-256.", exception);
        }
    }

    private record GroupKey(DiagnosticCategory category, String signature, int singletonPosition) {
    }
}
