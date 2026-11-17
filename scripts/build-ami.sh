#!/usr/bin/env bash
# Builds the AMI used by both the manager and the workers, and writes its id into Config.java.
#
#   1. Launch a temporary Ubuntu 24.04 instance whose user-data installs Java 17, tesseract + English
#      data and the AWS CLI v2, then powers the instance off.
#   2. Wait until it has stopped (= install succeeded), create an AMI from it, wait until it's available.
#   3. Terminate the temporary instance.
#
# Takes ~10 minutes. Rebuild only if the installed software changes; jars come from S3 at boot.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_aws

INSTALL_SCRIPT='#!/bin/bash
set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y openjdk-17-jre-headless tesseract-ocr tesseract-ocr-eng unzip curl
# Tess4J loads the native libraries by their plain names (libtesseract.so, libleptonica.so); only the
# -dev packages create those names (the runtime packages ship versioned files only).
apt-get install -y --no-install-recommends libtesseract-dev libleptonica-dev
# Older leptonica builds name the library liblept.so; make sure both plain names exist either way.
cd /usr/lib/x86_64-linux-gnu
if [ ! -e libleptonica.so ]; then ln -s "$(ls libleptonica.so.* liblept.so.* 2>/dev/null | head -n 1)" libleptonica.so; fi
if [ ! -e libtesseract.so ]; then ln -s "$(ls libtesseract.so.* 2>/dev/null | head -n 1)" libtesseract.so; fi
ldconfig
cd /
curl -sSfL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp
/tmp/aws/install
rm -rf /tmp/aws /tmp/awscliv2.zip
java -version
tesseract --version
/usr/local/bin/aws --version
ls /usr/share/tesseract-ocr/5/tessdata/eng.traineddata
# If either check fails, set -e stops here: the instance never powers off and the build reports an error.
ls -l /usr/lib/x86_64-linux-gnu/libtesseract.so /usr/lib/x86_64-linux-gnu/libleptonica.so
shutdown -h now
'

echo "Looking up the latest Ubuntu 24.04 AMI..."
BASE_AMI="$(aws ec2 describe-images --owners 099720109477 \
    --filters "Name=name,Values=ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*" "Name=state,Values=available" \
    --query 'sort_by(Images, &CreationDate)[-1].ImageId' --output text)"
echo "Base AMI: $BASE_AMI"

USER_DATA_FILE="$(mktemp)"
trap 'rm -f "$USER_DATA_FILE"' EXIT
printf '%s' "$INSTALL_SCRIPT" > "$USER_DATA_FILE"

BUILDER="$(aws ec2 run-instances --image-id "$BASE_AMI" --instance-type "$INSTANCE_TYPE" --count 1 \
    --user-data "file://$USER_DATA_FILE" \
    --tag-specifications 'ResourceType=instance,Tags=[{Key=Role,Value=ami-builder},{Key=Name,Value=dsp-ex1-ami-builder}]' \
    --query 'Instances[0].InstanceId' --output text)"
echo "Temporary instance: $BUILDER (installing; it powers off when done)"

# 'wait instance-stopped' treats "pending" as a failure, so first wait until the instance is running.
# The install then takes a few minutes; 'wait instance-stopped' gives up after 10 minutes, so allow two
# rounds (the first round's timeout message is hidden).
aws ec2 wait instance-running --instance-ids "$BUILDER"
echo "Instance running; installing software (about 5 minutes)..."
if ! aws ec2 wait instance-stopped --instance-ids "$BUILDER" 2>/dev/null \
    && ! aws ec2 wait instance-stopped --instance-ids "$BUILDER"; then
    echo "ERROR: the install did not finish. Last lines of the console output:" >&2
    aws ec2 get-console-output --instance-id "$BUILDER" --latest --output text | tail -n 30 >&2 || true
    aws ec2 terminate-instances --instance-ids "$BUILDER" >/dev/null
    echo "Temporary instance $BUILDER terminated." >&2
    exit 1
fi

NAME="dsp-ex1-ocr-$(date +%Y%m%d-%H%M%S)"
AMI_ID="$(aws ec2 create-image --instance-id "$BUILDER" --name "$NAME" \
    --description "DSP ex1: Ubuntu 24.04, Java 17, tesseract-ocr + eng, AWS CLI v2" \
    --query ImageId --output text)"
echo "Creating AMI $AMI_ID ($NAME)..."
aws ec2 wait image-available --image-ids "$AMI_ID"

aws ec2 terminate-instances --instance-ids "$BUILDER" >/dev/null
echo "Temporary instance $BUILDER terminated."

sed -i.bak "s/String AMI_ID = \".*\";/String AMI_ID = \"$AMI_ID\";/" "$CONFIG_JAVA" && rm -f "$CONFIG_JAVA.bak"
echo "AMI ready: $AMI_ID (written to Config.java). Next: scripts/build-and-upload.sh"
