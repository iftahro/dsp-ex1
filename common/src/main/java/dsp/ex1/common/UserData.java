package dsp.ex1.common;

/**
 * Startup scripts for new EC2 instances. The AMI already has Java, tesseract and the AWS CLI; the script
 * only fetches the latest jar from S3 and runs it, so new code never needs a new AMI.
 */
public final class UserData {

    private UserData() {}

    /**
     * Workers are stateless, so the java command runs in a loop: if the JVM crashes it comes back on the
     * same machine and simply continues with the next message.
     */
    public static String worker(String managerId) {
        return fetch("worker.jar")
                + "while true; do\n"
                + "  java -jar worker.jar " + managerId + " >> /var/log/dsp.log 2>&1\n"
                + "  sleep 5\n"
                + "done\n";
    }

    /**
     * The manager's jobs live in memory, so a restarted manager would have forgotten them while its
     * instance still looks alive, and its clients would wait forever. Instead, when the JVM exits for any
     * reason the instance shuts down (= terminates, see Ec2Instances.launch); clients see it is gone and
     * resubmit, and its orphaned workers terminate themselves.
     */
    public static String manager(int managerClientsN) {
        return fetch("manager.jar")
                + "java -jar manager.jar " + managerClientsN + " >> /var/log/dsp.log 2>&1\n"
                + "shutdown -h now\n";
    }

    private static String fetch(String jar) {
        return "#!/bin/bash\n"
                + "mkdir -p /opt/dsp && cd /opt/dsp\n"
                + "until aws s3 cp s3://" + Config.BUCKET + "/" + Config.JARS_PREFIX + jar + " " + jar
                + "; do sleep 5; done\n";
    }
}
