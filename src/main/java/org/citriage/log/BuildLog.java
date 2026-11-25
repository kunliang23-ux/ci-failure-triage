package org.citriage.log;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.citriage.report.ReportSource;

/** A decoded view of an immutable source snapshot. */
public final class BuildLog {
    private final ReportSource source;
    private final String text;
    private final List<LogLine> lines;

    BuildLog(ReportSource source, String text) {
        this.source = Objects.requireNonNull(source, "source");
        this.text = Objects.requireNonNull(text, "text");
        this.lines = splitLines(text);
    }

    public ReportSource source() {
        return source;
    }

    public String text() {
        return text;
    }

    public List<LogLine> lines() {
        return lines;
    }

    public boolean isBlank() {
        return text.startsWith("\uFEFF") ? text.substring(1).isBlank() : text.isBlank();
    }

    private static List<LogLine> splitLines(String text) {
        List<LogLine> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int lf = text.indexOf('\n', start);
            if (lf == -1) {
                lines.add(new LogLine(lines.size() + 1, text.substring(start), ""));
                break;
            }
            int end = lf > start && text.charAt(lf - 1) == '\r' ? lf - 1 : lf;
            lines.add(new LogLine(lines.size() + 1, text.substring(start, end), text.substring(end, lf + 1)));
            start = lf + 1;
        }
        return List.copyOf(lines);
    }
}
