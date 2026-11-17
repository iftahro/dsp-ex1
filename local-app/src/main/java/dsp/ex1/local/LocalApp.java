package dsp.ex1.local;

import dsp.ex1.common.AwsClients;
import dsp.ex1.common.Config;
import dsp.ex1.common.Ec2Instances;
import dsp.ex1.common.Json;
import dsp.ex1.common.Queues;
import dsp.ex1.common.UserData;
import dsp.ex1.common.messages.ManagerRequest;
import dsp.ex1.common.messages.OcrResult;
import dsp.ex1.common.messages.TaskDone;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Usage: java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
 *
 * Lives for one input file, then exits:
 *  1. create its own reply queue, upload the input to S3;
 *  2. find a manager tagged Accepting=true, or launch one (it gets manager-clients-n as its limit);
 *     if there is no room for a new instance, wait for room (up to 10 minutes);
 *  3. send NEW_TASK and wait for TaskDone, checking every minute that the manager is still alive;
 *     if it died, resubmit once to another (or a new) manager;
 *  4. download the summary and the texts, write the HTML;
 *  5. with terminate: send TERMINATE to the manager that served this job;
 *  6. always: delete the reply queue and this job's files on S3.
 *
 * Local testing: set DSP_LOCAL_MANAGER=<id> to use a manager started with --local <id>
 * (no EC2 calls: no lookup, no launch, no alive check).
 */
public class LocalApp {

    private static final int MAX_RESUBMITS = 1;
    private static final long MANAGER_CHECK_MS = 60_000;
    /** When there is no manager and no room to launch one: retry every 20 s, for up to 10 minutes. */
    private static final long ROOM_RETRY_MS = 20_000;
    private static final long ROOM_WAIT_MS = 10 * 60_000;
    private static final String LOCAL_MANAGER_ENV = "DSP_LOCAL_MANAGER";

    private final String taskId = UUID.randomUUID().toString();
    private final String localManagerId = System.getenv(LOCAL_MANAGER_ENV);
    private String replyQueueUrl;

    public static void main(String[] args) {
        if (args.length < 4 || args.length > 5 || (args.length == 5 && !args[4].equals("terminate"))) {
            System.err.println("Usage: java -jar local-app.jar inputFileName outputFileName "
                    + "manager-clients-n worker-files-n [terminate]");
            System.exit(1);
        }
        Path inputFile = Path.of(args[0]);
        Path outputFile = Path.of(args[1]);
        int managerClientsN;
        int workerFilesN;
        try {
            managerClientsN = Integer.parseInt(args[2]);
            workerFilesN = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            System.err.println("manager-clients-n and worker-files-n must be whole numbers");
            System.exit(1);
            return;
        }
        if (managerClientsN < 1 || workerFilesN < 1) {
            System.err.println("manager-clients-n and worker-files-n must be at least 1");
            System.exit(1);
        }
        if (!Files.isRegularFile(inputFile)) {
            System.err.println("Input file not found: " + inputFile);
            System.exit(1);
        }

        boolean ok = new LocalApp().run(inputFile, outputFile, managerClientsN, workerFilesN, args.length == 5);
        System.exit(ok ? 0 : 1);
    }

    boolean run(Path inputFile, Path outputFile, int managerClientsN, int workerFilesN, boolean terminate) {
        String inputKey = Config.INPUTS_PREFIX + taskId + ".txt";
        String managerId = null;
        try {
            replyQueueUrl = Queues.create(Config.REPLY_QUEUE_PREFIX + taskId);
            S3Files.upload(inputKey, inputFile);
            log("Job " + taskId + ": input uploaded to s3://" + Config.BUCKET + "/" + inputKey);

            TaskDone done = null;
            for (int attempt = 0; attempt <= MAX_RESUBMITS && done == null; attempt++) {
                if (attempt > 0) {
                    log("Manager " + managerId + " is gone; resubmitting the job");
                }
                managerId = findOrLaunchManager(managerClientsN);
                if (managerId == null) {
                    return false;
                }
                sendRequest(managerId, ManagerRequest.newTask(taskId, inputKey, replyQueueUrl, workerFilesN));
                log("Job sent to manager " + managerId + ", waiting for the result");
                done = waitForDone(managerId);
            }
            if (done == null) {
                log("ERROR: the manager died again; giving up");
                return false;
            }
            if (done.failed()) {
                log("ERROR: the manager could not process the job: " + done.error());
                return false;
            }

            List<OcrResult> results = S3Files.readSummary(done.summaryS3Key());
            Map<Integer, String> texts = S3Files.readTexts(results);
            HtmlReport.write(outputFile, results, texts);
            long failed = results.stream().filter(OcrResult::failed).count();
            log("Wrote " + outputFile + " (" + results.size() + " images, " + failed + " failed)");
            return true;
        } catch (Exception e) {
            log("ERROR: " + e);
            return false;
        } finally {
            if (terminate && managerId != null) {
                sendTerminate(managerId);
            }
            cleanUp(inputKey);
        }
    }

    /**
     * Any manager tagged Accepting=true, or a newly launched one. If there is neither a manager nor room for
     * a new instance (the 9-instance limit), waits and tries again, up to ROOM_WAIT_MS. Null if it gives up.
     */
    private String findOrLaunchManager(int managerClientsN) throws InterruptedException {
        if (localManagerId != null) {
            return localManagerId;
        }
        long giveUpAt = System.currentTimeMillis() + ROOM_WAIT_MS;
        while (true) {
            List<Instance> available = Ec2Instances.findActive(Map.of(
                    Config.TAG_ROLE, Config.ROLE_MANAGER,
                    Config.TAG_ACCEPTING, "true"));
            if (!available.isEmpty()) {
                return available.get(0).instanceId();
            }
            if (Ec2Instances.countActive() < Config.MAX_INSTANCES) {
                // This client is the new manager's first client, so with manager-clients-n = 1 the manager is
                // full from the start. Without this, clients arriving while it boots would all join it.
                List<String> launched = Ec2Instances.launch(1, UserData.manager(managerClientsN), Map.of(
                        Config.TAG_ROLE, Config.ROLE_MANAGER,
                        Config.TAG_ACCEPTING, String.valueOf(managerClientsN > 1)));
                log("No available manager; launched " + launched.get(0));
                return launched.get(0);
            }
            if (System.currentTimeMillis() >= giveUpAt) {
                log("ERROR: no manager became available and there was no room for a new one ("
                        + Config.MAX_INSTANCES + "-instance limit) for " + ROOM_WAIT_MS / 60_000 + " minutes");
                return null;
            }
            log("No available manager and " + Config.MAX_INSTANCES + " instances already running; "
                    + "waiting for room...");
            Thread.sleep(ROOM_RETRY_MS);
        }
    }

    /**
     * Creating the queue here (the manager creates it too; it's the same queue) lets the request wait in it
     * while a new manager boots, so this app never waits for the machine to come up.
     */
    private static void sendRequest(String managerId, ManagerRequest request) {
        String queueUrl = Queues.create(Config.requestsQueue(managerId));
        AwsClients.SQS.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody(Json.write(request)).build());
    }

    /** The TaskDone for this job, or null if the manager stopped running before answering. */
    private TaskDone waitForDone(String managerId) {
        long nextCheck = System.currentTimeMillis() + MANAGER_CHECK_MS;
        while (true) {
            TaskDone done = receiveDone(Config.LONG_POLL_WAIT_SEC);
            if (done != null) {
                return done;
            }
            if (localManagerId == null && System.currentTimeMillis() >= nextCheck) {
                nextCheck = System.currentTimeMillis() + MANAGER_CHECK_MS;
                if (!Ec2Instances.isActive(managerId)) {
                    // It may have answered just before going away: one last short look.
                    return receiveDone(1);
                }
            }
        }
    }

    private TaskDone receiveDone(int waitSeconds) {
        List<Message> messages = AwsClients.SQS.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(replyQueueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(waitSeconds)
                .build()).messages();
        for (Message message : messages) {
            AwsClients.SQS.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(replyQueueUrl).receiptHandle(message.receiptHandle()).build());
            TaskDone done = Json.read(message.body(), TaskDone.class);
            if (taskId.equals(done.taskId())) {
                return done;
            }
        }
        return null;
    }

    /** Uses the existing queue only: if the manager is already gone, there is nothing to terminate. */
    private void sendTerminate(String managerId) {
        try {
            String queueUrl = AwsClients.SQS.getQueueUrl(GetQueueUrlRequest.builder()
                    .queueName(Config.requestsQueue(managerId)).build()).queueUrl();
            AwsClients.SQS.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl).messageBody(Json.write(ManagerRequest.terminate())).build());
            log("Sent TERMINATE to manager " + managerId
                    + "; it shuts down after finishing its remaining jobs");
        } catch (QueueDoesNotExistException e) {
            log("Manager " + managerId + " has already shut down; no TERMINATE needed");
        } catch (Exception e) {
            log("Could not send TERMINATE to manager " + managerId + ": " + e);
        }
    }

    /** Deletes the reply queue and every file this job put on S3. Never throws. */
    private void cleanUp(String inputKey) {
        try {
            if (replyQueueUrl != null) {
                Queues.delete(replyQueueUrl);
            }
            S3Files.deleteJobFiles(taskId, inputKey);
        } catch (Exception e) {
            log("Cleanup failed (run the cleanup script): " + e);
        }
    }

    static void log(String message) {
        System.out.println("[local] " + message);
    }
}
