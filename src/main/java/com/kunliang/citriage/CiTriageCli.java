package com.kunliang.citriage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Properties;

/** The Day 1 command-line entry point. */
public final class CiTriageCli {
    private static final String HELP = """
            CI Failure Triage
            Help developers answer: Why did my tests fail, and what should I investigate first?

            Day 1 provides the CLI scaffold. Report and log analysis will be added later.

            Usage: java -jar target/ci-failure-triage.jar [--help | --version]

            Arguments (choose at most one):
              --help     Show this help message.
              --version  Show the application version.

            With no arguments, show help.
            Exit codes: 0 = success; 2 = invalid arguments.
            """;

    private CiTriageCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Handles arguments using caller-owned streams and returns an exit code without exiting. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        // Validate every argument before producing successful output.
        for (String argument : args) {
            if (!"--help".equals(argument) && !"--version".equals(argument)) {
                return argumentError(err, "Unknown argument: '" + argument + "'.");
            }
        }

        if (args.length > 1) {
            return argumentError(err, "Choose only one argument: --help or --version.");
        }

        if (args.length == 0 || "--help".equals(args[0])) {
            out.print(HELP);
        } else {
            out.println("CI Failure Triage " + applicationVersion());
        }
        return 0;
    }

    private static int argumentError(PrintStream err, String message) {
        err.println("Error: " + message);
        err.println("Run with --help for usage.");
        return 2;
    }

    private static String applicationVersion() {
        Properties properties = new Properties();
        try (InputStream input = CiTriageCli.class.getResourceAsStream("/ci-triage.properties")) {
            if (input == null) {
                throw new IllegalStateException("Application version resource is missing.");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read application version.", exception);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isBlank() || version.contains("${")) {
            throw new IllegalStateException("Application version was not supplied by Maven.");
        }
        return version;
    }
}
