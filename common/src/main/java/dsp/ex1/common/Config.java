package dsp.ex1.common;

import software.amazon.awssdk.regions.Region;

/** Shared constants. Nothing secret lives here: credentials come from ~/.aws (local) or the IAM role (EC2). */
public final class Config {

    private Config() {}

    public static final Region REGION = Region.US_EAST_1;

    // TODO: pick a globally unique bucket name. All project files go under it so cleanup is one delete.
    public static final String BUCKET = "dsp-ex1-ocr-CHANGE-ME";
    public static final String INPUTS_PREFIX = "inputs/";
    public static final String SUMMARIES_PREFIX = "summaries/";
    public static final String JARS_PREFIX = "jars/";

    // Shared queues. Each local app also creates its own reply queue: REPLY_QUEUE_PREFIX + <appId>.
    public static final String APP_TO_MANAGER_QUEUE = "dsp-ex1-app-to-manager";
    public static final String MANAGER_TO_WORKERS_QUEUE = "dsp-ex1-manager-to-workers";
    public static final String WORKERS_TO_MANAGER_QUEUE = "dsp-ex1-workers-to-manager";
    public static final String REPLY_QUEUE_PREFIX = "dsp-ex1-reply-";

    // EC2
    public static final String ROLE_TAG_KEY = "Role";
    public static final String ROLE_MANAGER = "manager";
    public static final String ROLE_WORKER = "worker";
    public static final String INSTANCE_PROFILE = "LabInstanceProfile";
    // TODO: fill in your AMI id (Java + tesseract installed) and instance type.
    public static final String AMI_ID = "ami-CHANGE-ME";
    public static final String INSTANCE_TYPE = "t2.micro";

    /** AWS student limit is 19 instances total; 1 is the manager. */
    public static final int MAX_WORKERS = 18;

    /** How long a worker has to finish one image before SQS hands the message to someone else. */
    public static final int WORKER_VISIBILITY_TIMEOUT_SEC = 120;
    public static final int LONG_POLL_WAIT_SEC = 20;
}
