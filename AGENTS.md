# Contributor instructions for Codex

- Read README.md and ROADMAP.md before changing behavior. Implement only the stage the user requests. Current scope covers the Maven CLI, local JUnit XML import, local log classification, and basic GitHub Actions CI. Read docs/JUNIT_XML_IMPORT.md or docs/LOG_IMPORT.md before changing the corresponding input contract.
- Inspect the folder and Git status before editing. Preserve existing user changes and never reset, overwrite, or delete unrelated work. Do not commit or push unless requested.
- Make focused changes that address the current task. Keep Java 17 compatibility, Maven's standard layout, and dependency versions explicit. Explain any new dependency or architectural decision.
- Keep public documentation and checked-in examples machine-independent. Use clearly labeled placeholder paths; exclude developer account names, actual home-directory paths, personal email addresses, and machine-inspection transcripts. Keep local validation output in ignored directories.
- Keep argument handling testable through `CiTriageCli.run(args, out, err)`. Only `main()` may call `System.exit()`. Do not close caller-owned output streams.
- Preserve evidence. Retain original bytes, source names, and their hashes. Distinguish XML-decoded fields and SAX diagnostic positions from exact log line ranges. Preserve original log text, line endings, and one-based line numbers. Normalize only a separate matching view. Take commit and environment metadata from explicit inputs. Use sanitized fixtures and preserve meaningful differences.
- Distinguish observations, inferred explanations, and unknowns. Never invent supporting log lines or claim a check ran when it did not. Insufficient evidence is an acceptable result.
- Add meaningful tests when behavior changes. Run relevant checks, normally `mvn test` and `mvn clean verify` for Java or build changes. For CLI or packaging changes, also run the packaged JAR with `--help`, `--version`, and an invalid option; verify output streams and process exit codes.
- If a check cannot run, report the exact blocker and the setup or follow-up needed. Do not present an unverified result as passing.
- Update documentation when setup, commands, scope, or behavior changes. Explain the final changes, decisions, validation results, and any remaining limitations in the handoff.
