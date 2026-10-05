#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$project_dir"

for executable in java docker python3; do
  if ! command -v "$executable" >/dev/null 2>&1; then
    echo "Required command is unavailable: $executable" >&2
    exit 1
  fi
done

if ! docker info >/dev/null 2>&1; then
  echo "A working Docker daemon is required for MySQL/Redis integration tests." >&2
  echo "Configure Docker in the cloud environment before publishing it." >&2
  exit 1
fi

java -version
RECEIPT_EXTRACTOR_PROVIDER=fake bash ./gradlew clean test --no-daemon

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

reports = sorted(Path("build/test-results/test").glob("TEST-*.xml"))
if not reports:
    raise SystemExit("No JUnit XML reports found.")

suites = [ET.parse(report).getroot() for report in reports]
totals = {
    key: sum(int(suite.get(key, "0")) for suite in suites)
    for key in ("tests", "failures", "errors", "skipped")
}
print("Test results: " + ", ".join(f"{key}={value}" for key, value in totals.items()))

mysql_suites = [
    suite for suite in suites
    if suite.get("name", "").endswith(".MySqlSchemaIntegrationTest")
]
if not mysql_suites or sum(int(suite.get("tests", "0")) for suite in mysql_suites) < 3:
    raise SystemExit("The existing MySQL integration tests did not all run.")
for name, minimum in (("EmployeeAuthorizationIntegrationTest", 13), ("EmployeeMigrationIntegrationTest", 1),
                      ("RedisSessionIntegrationTest", 8), ("ReceiptStorageConfigurationTest", 6),
                      ("S3ReceiptStorageIntegrationTest", 3), ("ReceiptListingWorkflowIntegrationTest", 11)):
    matching = [s for s in suites if s.get("name", "").endswith("." + name)]
    if not matching or sum(int(s.get("tests", "0")) for s in matching) < minimum:
        raise SystemExit(f"Required integration suite did not run: {name}")
if totals["tests"] == 0 or any(totals[key] for key in ("failures", "errors", "skipped")):
    raise SystemExit("Cloud verification requires passing tests with no skipped tests.")
PY
