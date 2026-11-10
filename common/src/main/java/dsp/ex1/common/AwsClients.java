package dsp.ex1.common;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * One shared client per AWS service. SDK clients are thread-safe, so every thread can reuse them.
 * Credentials are resolved by the SDK's default chain (~/.aws/credentials locally, IAM role on EC2).
 */
public final class AwsClients {

    private AwsClients() {}

    public static final S3Client S3 = S3Client.builder().region(Config.REGION).build();
    public static final SqsClient SQS = SqsClient.builder().region(Config.REGION).build();
    public static final Ec2Client EC2 = Ec2Client.builder().region(Config.REGION).build();
}
