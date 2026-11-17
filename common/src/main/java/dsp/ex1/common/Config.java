package dsp.ex1.common;

import software.amazon.awssdk.regions.Region;

/** Shared constants. Nothing secret lives here: credentials come from ~/.aws (local) or the IAM role (EC2). */
public final class Config {

    private Config() {}

    public static final Region REGION = Region.US_EAST_1;

    // All project files go under one bucket so cleanup is one delete.
    public static final String BUCKET = "dsp-ex1-ocr-271";
    public static final String INPUTS_PREFIX = "inputs/";
    /** One summary per job: summaries/<taskId>.jsonl, one OcrResult JSON per line, ordered by index. */
    public static final String SUMMARIES_PREFIX = "summaries/";
    /** OCR text per image: results/<taskId>/<index>.txt */
    public static final String RESULTS_PREFIX = "results/";
    public static final String JARS_PREFIX = "jars/";

    // Queues. Every manager owns three queues, suffixed with its id (the EC2 instance id), so several
    // managers never read each other's messages. Each local app also owns a reply queue.
    private static final String REQUESTS_QUEUE_PREFIX = "dsp-ex1-requests-";
    private static final String TASKS_QUEUE_PREFIX = "dsp-ex1-tasks-";
    private static final String RESULTS_QUEUE_PREFIX = "dsp-ex1-results-";
    public static final String REPLY_QUEUE_PREFIX = "dsp-ex1-reply-";

    /** Local apps -> manager: NEW_TASK and TERMINATE. */
    public static String requestsQueue(String managerId) {
        return REQUESTS_QUEUE_PREFIX + managerId;
    }

    /** Manager -> its workers: one ImageTask per URL. */
    public static String tasksQueue(String managerId) {
        return TASKS_QUEUE_PREFIX + managerId;
    }

    /** Workers -> their manager: one OcrResult per image. */
    public static String resultsQueue(String managerId) {
        return RESULTS_QUEUE_PREFIX + managerId;
    }

    // EC2 tags
    public static final String TAG_ROLE = "Role";
    public static final String ROLE_MANAGER = "manager";
    public static final String ROLE_WORKER = "worker";
    /** On managers: "true" while it takes new clients, "false" once full or terminating. */
    public static final String TAG_ACCEPTING = "Accepting";
    /** On workers: the instance id of the manager that launched them. */
    public static final String TAG_MANAGER_ID = "ManagerId";

    // EC2 launch settings
    public static final String INSTANCE_PROFILE = "LabInstanceProfile";
    // Set by scripts/build-ami.sh (Ubuntu 24.04 + Java 17 + tesseract-ocr + eng data + AWS CLI v2).
    public static final String AMI_ID = "ami-0d3c2ca30017afda9";
    public static final String INSTANCE_TYPE = "t3.small";

    /**
     * Never more than 9 instances in the region (managers + workers). The spec's hard cap is 19, but the
     * Learner Lab allows only 9 running EC2 instances at once in us-east-1 and terminates any extra ones.
     */
    public static final int MAX_INSTANCES = 9;

    /** How long a worker has to finish one image before SQS hands the message to someone else. */
    public static final int WORKER_VISIBILITY_TIMEOUT_SEC = 120;
    public static final int LONG_POLL_WAIT_SEC = 20;
    /** How often a worker checks that its manager is still alive (orphaned workers stop themselves). */
    public static final int MANAGER_ALIVE_CHECK_SEC = 60;
}
