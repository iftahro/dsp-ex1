#!/usr/bin/env bash
# Builds the AMI used by both the manager and the workers, and writes its id into Config.java.
#
#   1. Launch a temporary Ubuntu 26.04 instance whose user-data installs Java, tesseract + English data
#      and the AWS CLI v2, then runs a real OCR test with our worker.jar (download + Tess4J + OCR).
#   2. Test passed: the instance powers off. Test failed: it terminates itself, and this script stops.
#   3. Create an AMI from the stopped instance, wait until it's available, terminate the instance.
#
# Needs worker.jar on S3 (run scripts/build-and-upload.sh once first).
# Takes ~10 minutes. Rebuild only if the installed software changes; jars come from S3 at boot.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_aws

# One of the sample images that is known to load; the build fails if OCR on it returns an error.
TEST_IMAGE="http://www.identifont.com/samples/bitstream/OCRA.gif"

INSTALL_SCRIPT='#!/bin/bash
set -euxo pipefail
# Any failure: terminate this instance, so build-ami.sh sees "terminated" right away and stops.
fail() {
    echo "AMI-BUILD-FAILED"
    TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
    ID=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
    /usr/local/bin/aws ec2 terminate-instances --region us-east-1 --instance-ids "$ID" || true
}
trap fail ERR
export DEBIAN_FRONTEND=noninteractive
apt-get update
# default-jre-headless: Ubuntu'"'"'s current Java; our jars are built for Java 17 and run on any newer one.
apt-get install -y default-jre-headless tesseract-ocr tesseract-ocr-eng unzip curl
# Tess4J loads the native libraries by their plain names (libtesseract.so, libleptonica.so); only the
# -dev packages create those names (the runtime packages ship versioned files only).
apt-get install -y --no-install-recommends libtesseract-dev libleptonica-dev
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
ls -l /usr/lib/x86_64-linux-gnu/libtesseract.so /usr/lib/x86_64-linux-gnu/libleptonica.so

# Real test of the worker'"'"'s OCR path: our jar, Tess4J, the native libraries and the language data.
mkdir -p /tmp/ocrtest && cd /tmp/ocrtest
/usr/local/bin/aws s3 cp s3://__BUCKET__/jars/worker.jar worker.jar
java -cp worker.jar dsp.ex1.worker.OcrCheck __TEST_IMAGE__ | tee result.txt
if grep -q "^ERROR" result.txt; then false; fi
test "$(grep -v "^===" result.txt | tr -cd "[:alnum:]" | wc -c)" -ge 5
echo "OCR test passed"
cd / && rm -rf /tmp/ocrtest
shutdown -h now
'
INSTALL_SCRIPT="${INSTALL_SCRIPT//__BUCKET__/$BUCKET}"
INSTALL_SCRIPT="${INSTALL_SCRIPT//__TEST_IMAGE__/$TEST_IMAGE}"

if ! aws s3api head-object --bucket "$BUCKET" --key jars/worker.jar >/dev/null 2>&1; then
    echo "ERROR: s3://$BUCKET/jars/worker.jar not found; run scripts/build-and-upload.sh first." >&2
    exit 1
fi

echo "Looking up the latest Ubuntu 26.04 AMI..."
BASE_AMI="$(aws ec2 describe-images --owners 099720109477 \
    --filters "Name=name,Values=ubuntu/images/hvm-ssd-gp3/ubuntu-resolute-26.04-amd64-server-*" "Name=state,Values=available" \
    --query 'sort_by(Images, &CreationDate)[-1].ImageId' --output text)"
echo "Base AMI: $BASE_AMI"

USER_DATA_FILE="$(mktemp)"
trap 'rm -f "$USER_DATA_FILE"' EXIT
printf '%s' "$INSTALL_SCRIPT" > "$USER_DATA_FILE"

# The instance profile lets the test download worker.jar and lets a failed build terminate itself.
BUILDER="$(aws ec2 run-instances --image-id "$BASE_AMI" --instance-type "$INSTANCE_TYPE" --count 1 \
    --iam-instance-profile Name=LabInstanceProfile \
    --user-data "file://$USER_DATA_FILE" \
    --tag-specifications 'ResourceType=instance,Tags=[{Key=Role,Value=ami-builder},{Key=Name,Value=dsp-ex1-ami-builder}]' \
    --query 'Instances[0].InstanceId' --output text)"
echo "Temporary instance: $BUILDER (installing and testing OCR; it powers off when done)"

# 'wait instance-stopped' treats "pending" as a failure, so first wait until the instance is running.
# The install then takes a few minutes; 'wait instance-stopped' gives up after 10 minutes, so allow two
# rounds (the first round's timeout message is hidden). A failed build terminates itself, which makes
# the waiter fail at once.
aws ec2 wait instance-running --instance-ids "$BUILDER"
echo "Instance running; installing software and testing OCR (about 5 minutes)..."
if ! aws ec2 wait instance-stopped --instance-ids "$BUILDER" 2>/dev/null \
    && ! aws ec2 wait instance-stopped --instance-ids "$BUILDER" 2>/dev/null; then
    echo "ERROR: the install or the OCR test failed. Last lines of the console output" >&2
    echo "(can lag a few minutes; see EC2 console -> instance $BUILDER -> Get system log):" >&2
    aws ec2 get-console-output --instance-id "$BUILDER" --latest --output text | tail -n 40 >&2 || true
    aws ec2 terminate-instances --instance-ids "$BUILDER" >/dev/null || true
    echo "Temporary instance $BUILDER terminated. No AMI was created." >&2
    exit 1
fi

NAME="dsp-ex1-ocr-$(date +%Y%m%d-%H%M%S)"
AMI_ID="$(aws ec2 create-image --instance-id "$BUILDER" --name "$NAME" \
    --description "DSP ex1: Ubuntu 26.04, Java, tesseract-ocr + eng, AWS CLI v2 (OCR-tested)" \
    --query ImageId --output text)"
echo "OCR test passed. Creating AMI $AMI_ID ($NAME)..."
aws ec2 wait image-available --image-ids "$AMI_ID"

aws ec2 terminate-instances --instance-ids "$BUILDER" >/dev/null
echo "Temporary instance $BUILDER terminated."

sed -i.bak "s/String AMI_ID = \".*\";/String AMI_ID = \"$AMI_ID\";/" "$CONFIG_JAVA" && rm -f "$CONFIG_JAVA.bak"
echo "AMI ready: $AMI_ID (written to Config.java). Next: scripts/build-and-upload.sh"
