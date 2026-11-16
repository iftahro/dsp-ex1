#!/usr/bin/env bash
# Finds everything this project may have left behind, and removes it after a y/N prompt:
#   - EC2 instances tagged Role=manager or Role=worker (not yet terminated)
#   - SQS queues named dsp-ex1-*
#   - S3 objects under inputs/, results/, summaries/ (jars/ is kept)
#
#   scripts/cleanup.sh           list, then ask before deleting
#   scripts/cleanup.sh --check   only list (use after a run to verify everything shut down)
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_aws

CHECK_ONLY=false
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=true

INSTANCES="$(aws ec2 describe-instances \
    --filters "Name=tag:Role,Values=manager,worker" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[].[InstanceId,Tags[?Key==`Role`]|[0].Value,State.Name]' --output text)"
QUEUES="$(aws sqs list-queues --queue-name-prefix dsp-ex1- --query 'QueueUrls[]' --output text)"
[[ "$QUEUES" == "None" ]] && QUEUES=""
PREFIXES="inputs/ results/ summaries/"
OBJECT_COUNT=0
for prefix in $PREFIXES; do
    count="$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "$prefix" --query 'length(Contents || `[]`)' --output text 2>/dev/null || echo 0)"
    OBJECT_COUNT=$((OBJECT_COUNT + count))
done

echo "Instances (Role=manager/worker):"
if [[ -n "$INSTANCES" ]]; then echo "$INSTANCES" | sed 's/^/  /'; else echo "  none"; fi
echo "Queues (dsp-ex1-*):"
if [[ -n "$QUEUES" ]]; then echo "$QUEUES" | tr '\t' '\n' | sed 's/^/  /'; else echo "  none"; fi
echo "S3 objects under $PREFIXES in $BUCKET: $OBJECT_COUNT"

if [[ -z "$INSTANCES" && -z "$QUEUES" && "$OBJECT_COUNT" == 0 ]]; then
    echo "Nothing left behind."
    exit 0
fi
if $CHECK_ONLY; then
    exit 0
fi

read -r -p "Delete all of the above? [y/N] " answer
[[ "$answer" == "y" || "$answer" == "Y" ]] || { echo "Nothing deleted."; exit 0; }

if [[ -n "$INSTANCES" ]]; then
    # shellcheck disable=SC2046
    aws ec2 terminate-instances --instance-ids $(echo "$INSTANCES" | awk '{print $1}') >/dev/null
    echo "Instances terminated."
fi
for url in $QUEUES; do
    aws sqs delete-queue --queue-url "$url"
done
[[ -n "$QUEUES" ]] && echo "Queues deleted."
for prefix in $PREFIXES; do
    aws s3 rm --recursive --only-show-errors "s3://$BUCKET/$prefix"
done
echo "S3 files deleted (jars/ kept)."
