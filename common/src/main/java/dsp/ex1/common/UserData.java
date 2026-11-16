package dsp.ex1.common;

/**
 * Startup scripts for new EC2 instances. The AMI already has Java, tesseract and the AWS CLI; the script
 * only fetches the latest jar from S3 and runs it, so new code never needs a new AMI.
 * The java command runs in a loop: if the JVM crashes, it comes back on the same machine.
 */
public final class UserData {

    private UserData() {}

    public static String worker(String managerId) {
        return script("worker.jar", managerId);
    }

    private static String script(String jar, String args) {
        return "#!/bin/bash\n"
                + "mkdir -p /opt/dsp && cd /opt/dsp\n"
                + "until aws s3 cp s3://" + Config.BUCKET + "/" + Config.JARS_PREFIX + jar + " " + jar
                + "; do sleep 5; done\n"
                + "while true; do\n"
                + "  java -jar " + jar + " " + args + " >> /var/log/dsp.log 2>&1\n"
                + "  sleep 5\n"
                + "done\n";
    }
}
