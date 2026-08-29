#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

version="$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout)"
artifacts=(
  "gtfs-preprocessor-${version}.jar"
  "gtfs-preprocessor-${version}-sbom.json"
  "gtfs-preprocessor-${version}-sbom.xml"
)

for artifact in "${artifacts[@]}"; do
  test -s "target/${artifact}" || {
    echo "Missing first-build artifact: target/${artifact}" >&2
    exit 1
  }
done

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

(
  cd target
  sha256sum "${artifacts[@]}"
) > "$scratch/first.sha256"

./mvnw --batch-mode --no-transfer-progress -DskipTests clean package

(
  cd target
  sha256sum "${artifacts[@]}"
) > "$scratch/second.sha256"

diff -u "$scratch/first.sha256" "$scratch/second.sha256"
echo "Reproducible build verified for ${version}:"
cat "$scratch/second.sha256"
