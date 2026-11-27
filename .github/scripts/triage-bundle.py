"""Capture one Maven execution and assemble a version 1 triage bundle."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


STAGING = Path(".ci-triage-staging")
BUNDLE = Path("ci-triage-bundle")
BUILD_LOG = STAGING / "build.log"
METADATA = STAGING / ".metadata.json"
MAVEN_COMMAND = ("mvn", "--batch-mode", "clean", "verify")
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_MANIFEST_BYTES = 256 * 1024
MAX_ZIP_ENTRIES = 256

CONTROLLED_FAILURE = """\
package org.citriage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlledFailureTest {
    @Test
    void reportsServiceUnavailable() {
        assertEquals(200, 503, "Controlled-failure demonstration");
    }
}
"""


def probe(*command):
    try:
        return subprocess.check_output(command, stderr=subprocess.DEVNULL,
                                       text=True, encoding="utf-8", timeout=15).strip()
    except (OSError, UnicodeError, subprocess.SubprocessError):
        return ""


def execution_metadata(controlled_failure, maven_executable):
    execution = {
        "test_command": " ".join(MAVEN_COMMAND),
        "test_configuration": ("maven-verify-with-controlled-failure-v1"
                               if controlled_failure else "maven-verify"),
        "environment": os.environ.get("TRIAGE_ENVIRONMENT"),
        "os": platform.system(),
        "architecture": platform.machine(),
    }
    version = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", probe(maven_executable, "--version"))
    maven = re.search(r"^Apache Maven (\S+)", version, re.MULTILINE)
    java = re.search(r"^Java version: ([^,\r\n]+), vendor: ([^,\r\n]+)", version, re.MULTILINE)
    if maven and java:
        execution["toolchain"] = f"Maven {maven[1]}, Java {java[1]} ({java[2]})"
    return {key: value for key, value in execution.items() if value}


def capture_metadata(controlled_failure, maven_executable):
    manifest = {
        "version": 1,
        "repository": os.environ["GITHUB_REPOSITORY"],
        "repository_id": int(os.environ["GITHUB_REPOSITORY_ID"]),
        "run_id": int(os.environ["GITHUB_RUN_ID"]),
        "attempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
        "workflow": os.environ["GITHUB_WORKFLOW"],
        "job": os.environ["GITHUB_JOB"],
        "execution": execution_metadata(controlled_failure, maven_executable),
    }
    for field, commit in (("tested_commit", probe("git", "rev-parse", "--verify", "HEAD")),
                          ("triggering_head_commit", os.environ.get("TRIAGE_TRIGGERING_HEAD", ""))):
        if re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", commit):
            manifest[field] = commit
    METADATA.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")


def build(controlled_failure):
    STAGING.mkdir()
    maven_executable = shutil.which(MAVEN_COMMAND[0]) or MAVEN_COMMAND[0]
    capture_metadata(controlled_failure, maven_executable)
    if controlled_failure:
        destination = Path("src/test/java/org/citriage/ControlledFailureTest.java")
        with destination.open("x", encoding="utf-8") as test:
            test.write(CONTROLLED_FAILURE)

    with BUILD_LOG.open("xb") as log:
        command = (maven_executable, *MAVEN_COMMAND[1:])
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as maven:
            for chunk in iter(lambda: maven.stdout.read1(65536), b""):
                log.write(chunk)
                sys.stdout.buffer.write(chunk)
                sys.stdout.buffer.flush()
            status = maven.wait()
    return status if status >= 0 else 128 - status


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def observed_classpath(report, repository, project_classes, hashes):
    property_element = ET.parse(report).find("./properties/property[@name='surefire.test.class.path']")
    if property_element is None or not property_element.get("value"):
        return None
    jars = []
    for item in property_element.get("value").split(os.pathsep):
        path = Path(item).resolve()
        if path in project_classes:
            continue
        if not path.is_relative_to(repository) or path.suffix != ".jar" or not path.is_file():
            return None
        if path not in hashes:
            hashes[path] = sha256(path)
        jars.append((path.relative_to(repository).as_posix(), hashes[path]))
    return jars or None


def dependency_fingerprint(reports):
    if not reports:
        return None
    repository = (Path.home() / ".m2" / "repository").resolve()
    project_classes = {Path("target", directory).resolve() for directory in ("classes", "test-classes")}
    hashes = {}
    try:
        classpath = observed_classpath(reports[0], repository, project_classes, hashes)
        if not classpath or any(observed_classpath(path, repository, project_classes, hashes) != classpath
                                for path in reports[1:]):
            return None
    except (OSError, ValueError, ET.ParseError):
        return None
    description = json.dumps(classpath, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    return "maven-test-jars-v1:sha256:" + hashlib.sha256(description).hexdigest()


def copy_reports():
    reports = []
    for plugin in ("surefire", "failsafe"):
        for report in sorted(Path("target", plugin + "-reports").glob("TEST-*.xml")):
            if report.is_symlink() or not report.is_file():
                continue
            if report.stat().st_size > MAX_FILE_BYTES:
                raise ValueError("A JUnit report exceeds the bundle entry size limit.")
            destination = STAGING / "reports" / plugin / report.name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(report, destination)
            reports.append(destination)
    return reports


def collect():
    manifest = json.loads(METADATA.read_text(encoding="utf-8"))
    reports = copy_reports()
    evidence = [BUILD_LOG, *reports]
    sizes = [path.stat().st_size for path in evidence]
    if len(evidence) + 4 > MAX_ZIP_ENTRIES or max(sizes) > MAX_FILE_BYTES:
        raise ValueError("The captured evidence exceeds the bundle entry count or size limit.")
    fingerprint = dependency_fingerprint(reports)
    if fingerprint:
        manifest["execution"]["dependency_fingerprint"] = fingerprint
    manifest["files"] = [
        {"path": path.relative_to(STAGING).as_posix(), "kind": "log" if path == BUILD_LOG else "junit",
         "size": size, "sha256": sha256(path)}
        for path, size in zip(evidence, sizes)
    ]
    manifest_bytes = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    if len(manifest_bytes) > MAX_MANIFEST_BYTES or sum(sizes) + len(manifest_bytes) > MAX_TOTAL_BYTES:
        raise ValueError("The triage manifest or total evidence exceeds the bundle size limit.")
    (STAGING / "manifest.json").write_bytes(manifest_bytes)
    METADATA.unlink()
    STAGING.rename(BUNDLE)
    print(f"Collected the build log and {len(reports)} JUnit report(s).")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("step", choices=("build", "collect"))
    parser.add_argument("--controlled-failure", action="store_true")
    args = parser.parse_args()
    if args.step == "build":
        return build(args.controlled_failure)
    collect()
    return 0


if __name__ == "__main__":
    sys.exit(main())
