#!/usr/bin/env bash
# Builds all jars and uploads manager.jar + worker.jar to s3://<bucket>/jars/, where new EC2 instances
# download them at boot. Creates the bucket (private) if it doesn't exist yet.
# Run again after every code change; no new AMI is needed.
set -euo pipefail
source "$(dirname "$0")/env.sh"
require_aws

echo "Building..."
(cd "$REPO_ROOT" && mvn -q clean package)

if ! aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
    echo "Creating bucket $BUCKET"
    aws s3api create-bucket --bucket "$BUCKET" >/dev/null
    aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
        BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
fi

for jar in manager worker; do
    aws s3 cp --only-show-errors "$REPO_ROOT/$jar/target/$jar.jar" "s3://$BUCKET/jars/$jar.jar"
    echo "Uploaded s3://$BUCKET/jars/$jar.jar"
done
echo "Local app: $REPO_ROOT/local-app/target/local-app.jar"
