#!/usr/bin/env bash
# Builds the submission zip: sources + class files (no libraries), the README, and the output HTML of a
# run on the sample file.
#
#   scripts/package-submission.sh <output.html> [more files to include...]
set -euo pipefail
source "$(dirname "$0")/env.sh"

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <output.html> [more files...]" >&2
    exit 2
fi
for file in "$@"; do
    [[ -f "$file" ]] || { echo "ERROR: $file not found" >&2; exit 1; }
done

ZIP="$REPO_ROOT/dsp-ex1-submission.zip"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
DEST="$STAGE/dsp-ex1"
mkdir -p "$DEST"

echo "Building..."
(cd "$REPO_ROOT" && mvn -q clean package)

cp "$REPO_ROOT/pom.xml" "$REPO_ROOT/README.md" "$DEST/"
for module in common local-app manager worker; do
    mkdir -p "$DEST/$module/target"
    cp "$REPO_ROOT/$module/pom.xml" "$DEST/$module/"
    cp -R "$REPO_ROOT/$module/src" "$DEST/$module/"
    cp -R "$REPO_ROOT/$module/target/classes" "$DEST/$module/target/"
done
# Helper scripts, but never the one that handles lab credentials.
mkdir -p "$DEST/scripts"
for script in "$REPO_ROOT"/scripts/*.sh; do
    [[ "$(basename "$script")" == "lab-creds.sh" ]] || cp "$script" "$DEST/scripts/"
done
mkdir -p "$DEST/output"
cp "$@" "$DEST/output/"

# Safety net: refuse to zip anything that looks like an AWS key or a private key.
# (This script itself contains the patterns, so it is skipped.)
if grep -rIlE --exclude=package-submission.sh 'AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|aws_secret_access_key|aws_session_token|BEGIN [A-Z ]*PRIVATE KEY' "$DEST"; then
    echo "ERROR: the files above look like they contain credentials; nothing was zipped." >&2
    exit 1
fi

rm -f "$ZIP"
(cd "$STAGE" && zip -qr "$ZIP" dsp-ex1)
unzip -l "$ZIP" | awk 'NR>3 {print $4}' | grep -v '^$' | grep -v '/$'
echo "Created $ZIP"
