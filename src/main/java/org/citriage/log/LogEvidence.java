package org.citriage.log;

import java.util.List;
import java.util.Objects;

import org.citriage.report.ReportSource;

/** A nonempty range of consecutive, unmodified lines in one source snapshot. */
public record LogEvidence(ReportSource source, List<LogLine> lines) {
    public LogEvidence {
        Objects.requireNonNull(source, "source");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An evidence range requires at least one line.");
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).number() - lines.get(i - 1).number() != 1) {
                throw new IllegalArgumentException("Evidence lines must be consecutive.");
            }
        }
    }

    public int firstLine() {
        return lines.get(0).number();
    }

    public int lastLine() {
        return lines.get(lines.size() - 1).number();
    }

    public String originalText() {
        StringBuilder text = new StringBuilder();
        for (LogLine line : lines) {
            text.append(line.text()).append(line.terminator());
        }
        return text.toString();
    }
}
