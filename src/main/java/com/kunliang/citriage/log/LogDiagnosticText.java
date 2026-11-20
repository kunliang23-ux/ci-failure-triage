package com.kunliang.citriage.log;

import java.util.regex.Pattern;

/** Recognizes presentation and context shared by classification and grouping. */
final class LogDiagnosticText {
    static final String JAVA_NAME = "[A-Za-z_$][\\w$]*";
    static final String QUALIFIED_NAME = JAVA_NAME + "(?:\\." + JAVA_NAME + ")*";
    static final Pattern SUREFIRE_TEST = Pattern.compile(
            "(" + QUALIFIED_NAME + ")\\.(" + JAVA_NAME + "(?:\\([^)]*\\))?(?:\\[\\d+])?)"
                    + "\\s+(?:--\\s+)?Time elapsed:\\s+.+?\\s+<<<\\s+(?:FAILURE|ERROR)!?");
    static final Pattern STACK_FRAME = Pattern.compile("at\\s+[^\\s(]+\\([^)]*\\)|\\.\\.\\.\\s+\\d+\\s+more");

    private static final String COLOR = "\u001b\\[[0-9;:]*m";
    private static final String SEVERITY = "\\[(?:ERROR|FATAL)]";
    private static final String PREFIX_PADDING = "[ \\t]*+(?:" + COLOR + "[ \\t]*+)*+";
    private static final Pattern ANSI_COLOR = Pattern.compile(COLOR);
    private static final Pattern DISPLAY_PREFIX = Pattern.compile(
            "^" + PREFIX_PADDING + "(?:" + SEVERITY + PREFIX_PADDING + ")?");
    static final Pattern ERROR_LEVEL = Pattern.compile("^" + SEVERITY + "\\s*");

    private LogDiagnosticText() {
    }

    static String matchingText(LogLine line) {
        return ANSI_COLOR.matcher(withoutInitialBom(line)).replaceAll("").strip();
    }

    static String diagnosticText(String line) {
        return ERROR_LEVEL.matcher(line).replaceFirst("");
    }

    static String signatureText(LogLine line) {
        // Keep the payload verbatim, including spaces and color escapes inside values.
        return DISPLAY_PREFIX.matcher(withoutInitialBom(line)).replaceFirst("");
    }

    private static String withoutInitialBom(LogLine line) {
        String text = line.text();
        if (line.number() == 1 && text.startsWith("\uFEFF")) {
            return text.substring(1);
        }
        return text;
    }
}
