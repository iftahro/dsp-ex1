#!/usr/bin/env bash
# Shared settings for the scripts in this folder. Sourced, not run.
# Credentials come from the dsp-lab profile (set up with scripts/lab-creds.sh set). An AWS_PROFILE already
# set in your shell is deliberately ignored, so these scripts never touch another account by accident.
# To use a different profile on purpose: DSP_AWS_PROFILE=<name> scripts/<script>.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_JAVA="$REPO_ROOT/common/src/main/java/dsp/ex1/common/Config.java"

export AWS_PROFILE="${DSP_AWS_PROFILE:-dsp-lab}"
export AWS_DEFAULT_REGION="us-east-1"
export AWS_PAGER=""

# Single source of truth: the bucket name in Config.java.
BUCKET="$(sed -n 's/.*String BUCKET = "\(.*\)";.*/\1/p' "$CONFIG_JAVA")"
INSTANCE_TYPE="$(sed -n 's/.*String INSTANCE_TYPE = "\(.*\)";.*/\1/p' "$CONFIG_JAVA")"

# Maven needs a JDK 17+. Use Homebrew's if none is on the PATH.
if ! java -version >/dev/null 2>&1; then
    for candidate in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /opt/homebrew/opt/openjdk; do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            export PATH="$JAVA_HOME/bin:$PATH"
            break
        fi
    done
fi

require_aws() {
    local arn
    if ! arn="$(aws sts get-caller-identity --query Arn --output text 2>/dev/null)"; then
        echo "ERROR: AWS profile '$AWS_PROFILE' has no valid credentials." >&2
        echo "Start the lab, then run: scripts/lab-creds.sh set" >&2
        exit 1
    fi
    echo "AWS profile $AWS_PROFILE: $arn"
}
