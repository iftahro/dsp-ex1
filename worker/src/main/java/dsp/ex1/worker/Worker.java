package dsp.ex1.worker;

import dsp.ex1.common.AwsClients;
import dsp.ex1.common.Config;
import dsp.ex1.common.Ec2Instances;
import dsp.ex1.common.InstanceMetadata;
import dsp.ex1.common.Json;
import dsp.ex1.common.messages.ImageTask;
import dsp.ex1.common.messages.OcrResult;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs on EC2 (tags Role=worker, ManagerId=<id>). Stateless loop, one image at a time:
 *   receive ImageTask -> download image -> OCR -> upload text to S3 -> send OcrResult -> delete message.
 *
 * Failure handling:
 *  - Bad image / download / OCR error: send OcrResult.failure(...) and move on (the manager reports it).
 *  - AWS error while reporting: the message is NOT deleted, so after the visibility timeout
 *    SQS hands it to another worker (or this one) again.
 *  - Worker dies mid-message: same as above, the message reappears after the visibility timeout.
 *  - A rare duplicate result is harmless: the S3 key is deterministic and the manager ignores repeated indexes.
 *  - Manager dies: nobody would ever terminate this worker, so every minute it checks that its manager
 *    instance is still running, and terminates itself if not.
 *
 * Usage: java -jar worker.jar <managerId> [--local]
 *   --local: run on your own machine against a manager started with --local <managerId>
 *   (no EC2 calls: no manager-alive check, no self-terminate).
 */
public class Worker {

    private static final int MAX_ERROR_LENGTH = 200;
    private static final long AWS_ERROR_BACKOFF_MS = 5_000;

    private final ImageDownloader downloader = new ImageDownloader();
    private final OcrService ocr = new OcrService();

    private final String managerId;
    private final boolean local;
    private String tasksQueueUrl;
    private String resultsQueueUrl;
    private long nextManagerCheck;

    Worker(String managerId, boolean local) {
        this.managerId = managerId;
        this.local = local;
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--local"))) {
            System.err.println("Usage: java -jar worker.jar <managerId> [--local]");
            System.exit(1);
        }
        new Worker(args[0], args.length == 2).run();
    }

    void run() {
        tasksQueueUrl = waitForQueueUrl(Config.tasksQueue(managerId));
        resultsQueueUrl = waitForQueueUrl(Config.resultsQueue(managerId));
        log("Worker started for manager " + managerId + ". Listening on " + tasksQueueUrl);

        while (true) {
            try {
                if (!local && managerIsGone()) {
                    log("Manager " + managerId + " is not running any more, terminating this instance");
                    Ec2Instances.terminate(List.of(InstanceMetadata.instanceId()));
                    return;
                }
                for (Message message : receiveOne()) {
                    handle(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log("Interrupted, exiting");
                return;
            } catch (Exception e) {
                // AWS/network trouble: don't die, wait a little and try again.
                log("Loop error: " + describe(e));
                sleepQuietly(AWS_ERROR_BACKOFF_MS);
            }
        }
    }

    /** Checks at most once per MANAGER_ALIVE_CHECK_SEC; between checks it assumes the manager is alive. */
    private boolean managerIsGone() {
        long now = System.currentTimeMillis();
        if (now < nextManagerCheck) {
            return false;
        }
        nextManagerCheck = now + Config.MANAGER_ALIVE_CHECK_SEC * 1000L;
        return !Ec2Instances.isActive(managerId);
    }

    private List<Message> receiveOne() {
        return AwsClients.SQS.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(tasksQueueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(Config.LONG_POLL_WAIT_SEC)
                .visibilityTimeout(Config.WORKER_VISIBILITY_TIMEOUT_SEC)
                .build()).messages();
    }

    private void handle(Message message) throws InterruptedException {
        ImageTask task;
        try {
            task = Json.read(message.body(), ImageTask.class);
        } catch (IllegalArgumentException e) {
            // Without a taskId we can't report it to anyone; drop it so it doesn't loop forever.
            log("Dropping malformed message: " + message.body());
            deleteMessage(message);
            return;
        }

        OcrResult result = process(task);
        // If either call throws, the message stays in the queue and will be retried.
        AwsClients.SQS.sendMessage(SendMessageRequest.builder()
                .queueUrl(resultsQueueUrl)
                .messageBody(Json.write(result))
                .build());
        deleteMessage(message);
        log((result.failed() ? "FAILED " : "OK ") + task.taskId() + "#" + task.index()
                + (result.failed() ? " - " + result.error() : ""));
    }

    /** Download + OCR + upload. Image/OCR problems become a failure result; S3 upload errors propagate. */
    private OcrResult process(ImageTask task) throws InterruptedException {
        String text;
        try {
            BufferedImage image = downloader.download(task.imageUrl());
            text = ocr.extractText(image);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception | Error e) {
            // Error too: a corrupt image can trigger e.g. OutOfMemoryError or native library errors.
            return OcrResult.failure(task, describe(e));
        }

        String key = Config.RESULTS_PREFIX + task.taskId() + "/" + task.index() + ".txt";
        AwsClients.S3.putObject(PutObjectRequest.builder()
                        .bucket(Config.BUCKET)
                        .key(key)
                        .contentType("text/plain; charset=utf-8")
                        .build(),
                RequestBody.fromString(text, StandardCharsets.UTF_8));
        return OcrResult.success(task, key);
    }

    private void deleteMessage(Message message) {
        AwsClients.SQS.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(tasksQueueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    /** The manager creates the queues; a worker that boots first waits for them instead of crashing. */
    private static String waitForQueueUrl(String queueName) {
        while (true) {
            try {
                return AwsClients.SQS.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                        .queueUrl();
            } catch (QueueDoesNotExistException e) {
                log("Queue " + queueName + " does not exist yet, retrying");
                sleepQuietly(AWS_ERROR_BACKOFF_MS);
            }
        }
    }

    /** Short, one-line description for the output file, e.g. "UnknownHostException: no.such.host". */
    static String describe(Throwable e) {
        // Some exceptions (e.g. ConnectException) have no message; the cause usually explains it.
        Throwable withMessage = e;
        while (withMessage.getMessage() == null && withMessage.getCause() != null) {
            withMessage = withMessage.getCause();
        }
        String text = e.getClass().getSimpleName();
        if (withMessage != e) {
            text += ": " + withMessage.getClass().getSimpleName();
        }
        if (withMessage.getMessage() != null) {
            text += ": " + withMessage.getMessage();
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH) + "...";
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String message) {
        System.out.println("[worker] " + message);
    }
}
