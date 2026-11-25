package org.citriage;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.citriage.report.SourceLocation;

/** Formats references for the console while leaving the saved evidence intact. */
final class CliDisplay {
    private static final String PROFILE_ROOT = "(?i:[a-z]:[/\\\\]+users[/\\\\]+|(?<![\\w])/(?:home|users)/)";
    private static final Pattern HOME_PATH = homePathPattern();

    private CliDisplay() {
    }

    static String sourceName(String path) {
        // Saved paths may come from a different operating system.
        int separator = path.startsWith("/") && !path.startsWith("//") ? path.lastIndexOf('/')
                : Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = path.substring(separator + 1);
        return name.isEmpty() ? "(source)" : text(name);
    }

    static String location(SourceLocation location) {
        String name = sourceName(location.sourceName());
        return location.display(name);
    }

    static String ioReason(Exception exception) {
        if (exception instanceof NoSuchFileException) {
            return "file does not exist.";
        }
        if (exception instanceof AccessDeniedException) {
            return "access denied.";
        }
        String reason = exception instanceof FileSystemException fileError ? fileError.getReason() : exception.getMessage();
        return reason == null ? "file I/O failure." : reason;
    }

    static String redactHomes(String value) {
        return HOME_PATH.matcher(value).replaceAll("<home>");
    }

    static String text(String value) {
        String redacted = redactHomes(value);
        StringBuilder display = new StringBuilder(redacted.length());
        for (int i = 0; i < redacted.length(); i++) {
            char character = redacted.charAt(i);
            if (Character.isISOControl(character) && character != '\t') {
                display.append("\\u00").append(Character.forDigit(character >> 4, 16))
                        .append(Character.forDigit(character & 0xf, 16));
            } else {
                display.append(character);
            }
        }
        return display.toString();
    }

    private static Pattern homePathPattern() {
        // The first form also accepts profile directory names with spaces before a path separator.
        String subpath = PROFILE_ROOT + "[^/\\\\\"'<>:|\\p{Cntrl}]+(?=[/\\\\])";
        String directory = PROFILE_ROOT + "[^/\\\\\\s\"'<>:|]+";
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return Pattern.compile(subpath + "|" + directory);
        }
        String systemHome = Arrays.stream(home.split("[/\\\\]+", -1)).map(Pattern::quote)
                .collect(Collectors.joining("[/\\\\]+"));
        return Pattern.compile(subpath + "|(?i:" + systemHome + ")(?=$|[/\\\\\\s\"'<>:|])|" + directory);
    }
}
