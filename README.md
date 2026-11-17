# CI Failure Triage

A Java portfolio project to help developers answer:

> Why did my tests fail, and what should I investigate first?

**Day 1 implements the project scaffold and CLI only.** It shows help and version information and rejects invalid arguments. Report parsing, log classification, grouping, comparisons, SQLite storage, AI explanations, GitHub importing, the Spring Boot API, and the React/TypeScript dashboard are planned in [ROADMAP.md](ROADMAP.md).

## Requirements and versions

- JDK 17, including `java` and `javac`.
- Apache Maven 3.9.x or a newer stable Maven 3 release; the local setup below uses 3.9.16.
- Git for source control and a GitHub account for publishing.
- Internet access for the first Maven dependency download. The built CLI itself works offline.

`pom.xml` targets Java 17 with `maven.compiler.release`. JUnit Jupiter 5.13.4 is test-only; there are no runtime dependencies. The active build plugins are pinned: Clean 3.4.1, Resources 3.3.1, Compiler 3.14.1, Surefire 3.5.4, and JAR 3.4.2. These are stable releases, rather than previews. See the [JUnit release notes](https://docs.junit.org/5.13.4/release-notes/), [Compiler requirements](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.14.1/plugin-info.html), [Surefire requirements](https://maven.apache.org/surefire-archives/surefire-3.5.4/maven-surefire-plugin/plugin-info.html), and [JAR requirements](https://maven.apache.org/plugins-archives/maven-jar-plugin-3.4.2/plugin-info.html).

## Windows PowerShell setup

Initial inspection of this machine found an empty project folder, Git installed, and no Git repository or remote. `java`, `javac`, and `mvn` were absent from `PATH`. A working Microsoft JDK 17 was found under the user's `.jdks` directory.

Open PowerShell in the project directory. The following enables that existing JDK **for this terminal session**:

```powershell
Set-Location 'C:\Users\kunli\Downloads\AI-assisted CI failure triage tool'
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks\ms-17.0.21.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
```

On another Windows machine, install a JDK 17 first using the [Microsoft OpenJDK installation instructions](https://learn.microsoft.com/en-us/java/openjdk/install), then set `JAVA_HOME` to its installation directory and add its `bin` directory to `PATH`. For a permanent setup, use Windows **Edit environment variables for your account**, then open a new terminal. Use a JDK rather than a JRE because Maven needs a compiler.

If Maven is not installed, this downloads a portable copy into ignored `.tools/`, checks its SHA-512 checksum, and extracts it. It does not change system settings or require an administrator terminal. The version comes from the [official Maven download page](https://maven.apache.org/download.cgi).

```powershell
$ErrorActionPreference = 'Stop'
$toolDirectory = Join-Path (Get-Location) '.tools'
$mavenVersion = '3.9.16'
$mavenDirectory = Join-Path $toolDirectory "apache-maven-$mavenVersion"
if (-not (Test-Path -LiteralPath "$mavenDirectory\bin\mvn.cmd")) {
    New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
    $archiveName = "apache-maven-$mavenVersion-bin.zip"
    $archivePath = Join-Path $toolDirectory $archiveName
    $checksumPath = "$archivePath.sha512"
    $baseUrl = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries"
    Invoke-WebRequest -Uri "$baseUrl/$archiveName" -OutFile $archivePath
    Invoke-WebRequest -Uri "$baseUrl/$archiveName.sha512" -OutFile $checksumPath
    $expectedHash = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0]
    $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash
    if ($actualHash -ine $expectedHash) { throw 'Maven archive SHA-512 verification failed.' }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolDirectory
}
$env:Path = "$mavenDirectory\bin;$env:Path"
mvn --version
```

Confirm that `mvn --version` reports the JDK 17 you selected. Repeat the session environment commands in a new terminal, or add Maven's `bin` directory to your user `PATH` permanently. An existing Maven installation can be used instead.

## Build, test, and run

From the project root, after setting up `PATH`:

```powershell
# Run all JUnit tests.
mvn test

# Clean, compile, run tests, package the JAR, and complete verification.
mvn clean verify

# Run the packaged CLI.
java -jar target/ci-failure-triage.jar --help
java -jar target/ci-failure-triage.jar --version

# Demonstrate an invalid argument and inspect its process exit code.
java -jar target/ci-failure-triage.jar --unknown
$LASTEXITCODE
```

`mvn package` also builds the JAR and runs tests. `mvn test` by itself does not package it. Build output is in `target/`; JUnit text/XML reports are in `target/surefire-reports/`.

For an isolated local Maven cache, use the following commands instead. This is the cache location used for validation in the managed development environment:

```powershell
mvn '-Dmaven.repo.local=.tools/maven-repository' test
mvn '-Dmaven.repo.local=.tools/maven-repository' clean verify
```

## Day 1 validation results

Validated on November 17, 2026, on Windows 11 x64 with Microsoft OpenJDK 17.0.21.0.2 and the checksum-verified portable Maven 3.9.16:

- Both isolated-cache Maven commands above completed with `BUILD SUCCESS`. Each ran 14 tests, with zero failures, errors, or skipped tests.
- The packaged JAR returned `0` for `--help`, `--version`, and no arguments. Version output was `CI Failure Triage 0.1.0`.
- It returned `2` for `--unknown`, `--help --unknown`, and `--help --version`, with guidance on standard error and no standard output.
- The JAR manifest names `com.kunliang.citriage.CiTriageCli`, its version resource contains `version=0.1.0`, and its class-file major version is `61` (Java 17).

JUnit results are retained in `target/surefire-reports/`. Packaged process-check results and captured streams are in `target/cli-smoke-results.json` and `target/demo-*.txt`. These are generated validation artifacts, so a subsequent `clean` removes them.

## CLI behavior

| Arguments | Standard output | Standard error | Exit code |
| --- | --- | --- | --- |
| No arguments or `--help` | Purpose, usage, available options | Empty | `0` |
| `--version` | `CI Failure Triage 0.1.0` | Empty | `0` |
| Any unknown argument, including a file path | Empty | Unknown argument and `--help` guidance | `2` |
| Multiple known options, including repeats | Empty | Choose one option and `--help` guidance | `2` |

All arguments are checked before printing successful output, so `--help --unknown` still fails. The only supported flags are `--help` and `--version`; input file arguments will be introduced later.

## Files and execution flow

| File | Purpose |
| --- | --- |
| `pom.xml` | Java 17 target, application version, test dependency, pinned plugins, fixed JAR name, and manifest entry point. |
| `src/main/java/com/kunliang/citriage/CiTriageCli.java` | `main()`, argument handling, help/error output, and version loading. |
| `src/main/resources/ci-triage.properties` | Version template; Maven replaces `${project.version}` with the POM version. |
| `src/test/java/com/kunliang/citriage/CiTriageCliTest.java` | JUnit tests for output streams, exit codes, no arguments, and invalid combinations. |
| `.gitignore` | Excludes build output, local tools, credentials, SQLite files, and editor/OS temporary files. Deliberately allows sanitized `.log` fixtures. |
| `AGENTS.md` | Future Codex instructions about scope, existing work, evidence, checks, and handoffs. |
| `ROADMAP.md` | Later implementation stages and their boundaries. |
| `README.md` | Project explanation, setup, commands, and handoff guidance. |

When you run `java -jar`, Java reads `META-INF/MANIFEST.MF` inside the JAR. Maven's JAR plugin writes `Main-Class: com.kunliang.citriage.CiTriageCli` there. Java calls `public static void main(String[] args)` on that class. `main()` passes the arguments and console streams to `run()`, then uses its return value as the process exit code.

`run()` returns an integer and never exits the JVM. Tests call it with in-memory streams, so argument errors cannot terminate JUnit. Maven filters the version resource before tests and packaging, keeping the application's version in `pom.xml`; the version test checks the expected release string. The plain JAR is sufficient because the CLI has no runtime dependencies.

`.tools/` contains ignored local tooling/cache files when the portable setup is used. `target/` contains generated files, including the executable JAR. Neither belongs in the source commit.

## Commit and push to GitHub

These are commands for you to run; the scaffold task does not commit or push. No existing remote was configured during initial inspection.

The connected GitHub account is `kunliang23-ux`. If the repository does not exist yet, create an **empty** repository named `ci-failure-triage` under that account, without initializing it with a README, license, or `.gitignore`. Then run:

```powershell
# Only if this directory is still not a Git repository:
git init -b main

# Review the files and stage only project sources/documentation.
git status --short
git add pom.xml src README.md .gitignore AGENTS.md ROADMAP.md
git diff --cached --check
git diff --cached
git commit -m "Add Day 1 Java CLI scaffold"

# Inspect remotes before adding one.
git remote -v

# Only when origin is absent:
git remote add origin https://github.com/kunliang23-ux/ci-failure-triage.git

# Verify the destination, then publish the current branch.
git remote -v
git push -u origin HEAD
```

If an existing `origin` is configured, use it and skip `git remote add`; do not overwrite it. If an existing remote has another name, use that name in the push command. GitHub authentication must already be configured. If Git asks for an author identity, set your chosen `user.name` and `user.email` before committing.

## Before Day 2

Understand Maven's `test`, `package`, and `verify` phases; how the JAR reaches `main()`; and why the argument handler returns an exit code instead of calling `System.exit()`. Be able to run tests and find their reports. Next, agree on a small JUnit XML input/output contract, model test outcomes, and add sanitized fixtures. Preserve original reports and evidence while parsing; leave storage and AI for their roadmap stages.
