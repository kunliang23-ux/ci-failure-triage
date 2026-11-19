package com.kunliang.citriage.log;

import java.util.Objects;

/** One physical source line, with its original terminator stored separately. */
public record LogLine(int number, String text, String terminator) {
    public LogLine {
        if (number < 1) {
            throw new IllegalArgumentException("Log line numbers start at 1.");
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(terminator, "terminator");
    }
}
