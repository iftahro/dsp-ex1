package dsp.ex1.common;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * One shared client per AWS service. SDK clients are thread-safe, so every thread can reuse them.
 *
 * Credentials:
 *  - On your machine: the dsp-lab profile in ~/.aws (scripts/lab-creds.sh set), or DSP_AWS_PROFILE if set.
 *    An AWS_PROFILE in your shell is ignored on purpose, so the app never runs in another account by accident.
 *  - Otherwise (on EC2): only the instance's IAM role (LabInstanceProfile). Never the generic default chain,
 *    which would pick up AWS_PROFILE or other keys. Without the profile on your machine this fails with a
 *    clear error instead of using another account. No keys are ever stored in a jar or in user-data.
 */
public final class AwsClients {

    private AwsClients() {}

    private static final String PROFILE = System.getenv().getOrDefault("DSP_AWS_PROFILE", "dsp-lab");
    private static final AwsCredentialsProvider CREDENTIALS = credentials();

    public static final S3Client S3 = S3Client.builder().region(Config.REGION).credentialsProvider(CREDENTIALS).build();
    public static final SqsClient SQS = SqsClient.builder().region(Config.REGION).credentialsProvider(CREDENTIALS).build();
    public static final Ec2Client EC2 = Ec2Client.builder().region(Config.REGION).credentialsProvider(CREDENTIALS).build();

    private static AwsCredentialsProvider credentials() {
        boolean hasProfile;
        try {
            hasProfile = ProfileFile.defaultProfileFile().profile(PROFILE).isPresent();
        } catch (RuntimeException e) {
            hasProfile = false;
        }
        return hasProfile
                ? ProfileCredentialsProvider.create(PROFILE)
                : InstanceProfileCredentialsProvider.builder().build();
    }
}
