package org.citriage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.citriage.storage.SqliteRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiTriagePrivacyTest {
    @TempDir
    Path directory;

    @Test
    void masksHomePathsInConsoleEvidenceAndPreservesSavedSnapshots() throws Exception {
        String windowsHome = "C:\\Users\\Example Profile";
        String unixHome = "/home/example-profile";
        Path report = Files.writeString(directory.resolve("report.xml"), """
                <testsuite name="fixture"><testcase classname="example.Test" name="sample">
                  <failure message="Cannot read %s\\job\\settings.xml">Replay from %s/job/trace.txt</failure>
                </testcase></testsuite>
                """.formatted(windowsHome, unixHome));
        Path log = Files.writeString(directory.resolve("job.log"),
                "[ERROR] Cannot read " + windowsHome + "\\job\\settings.xml\n");
        Path db = directory.resolve("runs.sqlite");

        var xml = invoke("import-junit", report.toString(), "--db", db.toString(), "--run-id", "run");
        var grouped = invoke("import-log", log.toString(), "--db", db.toString(), "--run-id", "run", "--group");
        var stored = invoke("show-run", "run", "--db", db.toString(), "--group");
        var compared = invoke("compare-runs", "run", "--db", db.toString());
        for (Result result : new Result[]{xml, grouped, stored, compared}) {
            assertEquals(0, result.code(), result.err());
            assertEquals("", result.err());
            assertFalse(result.out().contains(directory.toAbsolutePath().normalize().toString()), result.out());
            assertFalse(result.out().contains(windowsHome), result.out());
            assertFalse(result.out().contains(unixHome), result.out());
            assertTrue(result.out().contains("<home>\\job\\settings.xml"), result.out());
        }
        assertTrue(xml.out().contains("Report: report.xml"));
        assertTrue(xml.out().contains("<home>/job/trace.txt"));
        assertTrue(grouped.out().contains("Log: job.log"));
        assertTrue(compared.out().contains("Evidence: job.log:1"));
        assertTrue(compared.out().contains("XML diagnostic location: report.xml:"));

        var run = new SqliteRunRepository(db).findRun("run").orElseThrow();
        var savedReport = run.reports().get(0).report();
        var savedLog = run.logs().get(0).log();
        assertEquals(report.toAbsolutePath().normalize().toString(), savedReport.source().name());
        assertEquals(log.toAbsolutePath().normalize().toString(), savedLog.source().name());
        assertArrayEquals(Files.readAllBytes(report), savedReport.source().bytes());
        assertArrayEquals(Files.readAllBytes(log), savedLog.source().bytes());
    }

    @Test
    void errorsUseFilenamesAndRetainDiagnostics() throws Exception {
        Path malformed = Files.writeString(directory.resolve("malformed.xml"), "<testsuite>");
        assertError(invoke("import-junit", malformed.toString()), "malformed.xml:1:");
        assertError(invoke("import-log", directory.resolve("missing.log").toString()), "Cannot read log 'missing.log'");
        assertError(invoke("runs", "--db", directory.resolve("missing.sqlite").toString()), "Database does not exist: missing.sqlite");
        assertError(invoke("import-junit", malformed.toString(), "--db", directory.resolve("runs.sqlite").toString(),
                "--metadata", directory.resolve("missing.properties").toString()), "Cannot use metadata 'missing.properties'");
    }

    @Test
    void formatsStoredPathsFromMultipleOperatingSystems() {
        assertEquals("test.xml", CliDisplay.sourceName("C:\\Users\\Sample Account\\results\\test.xml"));
        assertEquals("test.xml", CliDisplay.sourceName("/home/sample/results/test.xml"));
        assertEquals("test.xml", CliDisplay.sourceName("\\\\server\\share\\results\\test.xml"));
        assertEquals("a\\b.xml", CliDisplay.sourceName("/home/sample/a\\b.xml"));
        assertEquals("Look in <home>", CliDisplay.text("Look in C:\\Users\\sample"));
    }

    private void assertError(Result result, String expected) {
        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains(expected), result.err());
        assertFalse(result.err().contains(directory.toAbsolutePath().normalize().toString()), result.err());
    }

    private static Result invoke(String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        try (var out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             var err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            return new Result(CiTriageCli.run(args, out, err), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private record Result(int code, String out, String err) {
    }
}
