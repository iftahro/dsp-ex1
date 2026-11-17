package dsp.ex1.manager;

import dsp.ex1.common.AwsClients;
import dsp.ex1.common.Config;
import dsp.ex1.common.Ec2Instances;
import dsp.ex1.common.InstanceMetadata;
import dsp.ex1.common.Json;
import dsp.ex1.common.Queues;
import dsp.ex1.common.messages.ManagerRequest;
import dsp.ex1.common.messages.OcrResult;
import dsp.ex1.common.messages.TaskDone;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs on EC2 (tags Role=manager, Accepting=true|false). Serves up to managerClientsN local apps at once
 * until it gets TERMINATE.
 *
 * Usage: java -jar manager.jar <managerClientsN> [--local <id>]
 *   --local: run on your own machine with the given id. No EC2 calls at all (no tags, no workers launched,
 *   no self-terminate); start workers by hand with the same id.
 *
 * Threads (a fixed number, however many clients):
 *  - request listener (main thread): reads this manager's request queue, one message at a time, always,
 *    because TERMINATE arrives there too.
 *  - job pool (3): download an input file, queue its ImageTasks, scale workers.
 *  - result listeners (3): record OcrResults; the thread that completes a job writes its summary.
 *  - monitor (1): every 30s re-checks the worker count, so dead workers are replaced, and terminates the
 *    workers once the manager has had no jobs for 5 minutes (the manager itself stays up for new clients).
 */
public class Manager {

    private static final int JOB_THREADS = 3;
    private static final int RESULT_THREADS = 3;
    private static final int MONITOR_PERIOD_SEC = 30;
    private static final long IDLE_WORKERS_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int TERMINATING_POLL_SEC = 5;
    private static final int SUMMARY_ATTEMPTS = 3;
    private static final long ERROR_BACKOFF_MS = 5_000;

    private final String managerId;
    private final int managerClientsN;
    private final boolean local;

    /** Every job in progress, by taskId. A job is removed once its client got a TaskDone. */
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    /** Guards terminating and the Accepting tag, so the tag always matches tasks.size() and terminating. */
    private final Object lock = new Object();
    private boolean terminating;
    /** Last value written to the Accepting tag; null until the first write. */
    private Boolean acceptingTag;
    private volatile boolean running = true;
    /** Last time the monitor saw at least one job. Only the monitor thread uses it. */
    private long lastBusy = System.currentTimeMillis();

    private String requestsQueueUrl;
    private String tasksQueueUrl;
    private String resultsQueueUrl;
    private WorkerScaler scaler;
    private JobStarter jobStarter;

    private final ExecutorService jobPool = Executors.newFixedThreadPool(JOB_THREADS, named("job"));
    private final ExecutorService resultPool = Executors.newFixedThreadPool(RESULT_THREADS, named("results"));
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(named("monitor"));

    Manager(String managerId, int managerClientsN, boolean local) {
        this.managerId = managerId;
        this.managerClientsN = managerClientsN;
        this.local = local;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && !(args.length == 3 && args[1].equals("--local"))) {
            System.err.println("Usage: java -jar manager.jar <managerClientsN> [--local <id>]");
            System.exit(1);
        }
        int managerClientsN = Integer.parseInt(args[0]);
        boolean local = args.length == 3;
        String managerId = local ? args[2] : InstanceMetadata.instanceId();
        new Manager(managerId, managerClientsN, local).run();
    }

    void run() {
        Thread.currentThread().setName("requests");
        requestsQueueUrl = Queues.create(Config.requestsQueue(managerId));
        tasksQueueUrl = Queues.create(Config.tasksQueue(managerId));
        resultsQueueUrl = Queues.create(Config.resultsQueue(managerId));
        scaler = new WorkerScaler(managerId);
        jobStarter = new JobStarter(this, tasksQueueUrl);
        log("Manager " + managerId + " started (max " + managerClientsN + " clients" + (local ? ", local mode" : "") + ")");

        // No Accepting tag write here: the client that launched this manager already set it correctly
        // (false if it alone fills manager-clients-n). The tag changes as jobs are accepted and answered.
        for (int i = 0; i < RESULT_THREADS; i++) {
            resultPool.submit(new ResultCollector(this, resultsQueueUrl));
        }
        if (!local) {
            monitor.scheduleWithFixedDelay(this::monitorTick, MONITOR_PERIOD_SEC, MONITOR_PERIOD_SEC, TimeUnit.SECONDS);
        }

        listenForRequests();
        shutDown();
    }

    /** Runs until TERMINATE was received and every accepted job has been answered. */
    private void listenForRequests() {
        while (!(isTerminating() && tasks.isEmpty())) {
            try {
                List<Message> messages = AwsClients.SQS.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(requestsQueueUrl)
                        .maxNumberOfMessages(1)
                        // Short polls while terminating, so shutdown isn't delayed by a 20s wait.
                        .waitTimeSeconds(isTerminating() ? TERMINATING_POLL_SEC : Config.LONG_POLL_WAIT_SEC)
                        .build()).messages();
                for (Message message : messages) {
                    // Deleted right away: a job's progress lives in memory, so redelivering the request
                    // later would only start it twice. Failures are answered with an error TaskDone.
                    AwsClients.SQS.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(requestsQueueUrl).receiptHandle(message.receiptHandle()).build());
                    handleRequest(message.body());
                }
            } catch (Exception e) {
                log("Request listener error: " + e);
                sleepQuietly(ERROR_BACKOFF_MS);
            }
        }
    }

    private void handleRequest(String body) {
        ManagerRequest request;
        try {
            request = Json.read(body, ManagerRequest.class);
        } catch (IllegalArgumentException e) {
            log("Dropping malformed request: " + body);
            return;
        }
        switch (request.type()) {
            case TERMINATE -> {
                synchronized (lock) {
                    terminating = true;
                    updateAcceptingTag();
                }
                log("TERMINATE received: no new jobs; waiting for " + tasks.size() + " active jobs");
            }
            case NEW_TASK -> acceptJob(request);
        }
    }

    private void acceptJob(ManagerRequest request) {
        TaskState task = new TaskState(request.taskId(), request.replyQueueUrl(), request.workerFilesN());
        synchronized (lock) {
            if (terminating) {
                reply(task.replyQueueUrl(), TaskDone.failure(task.taskId(), "Manager is terminating; job not accepted"));
                log("Rejected job " + task.taskId() + ": terminating");
                return;
            }
            if (tasks.putIfAbsent(task.taskId(), task) != null) {
                log("Ignoring duplicate request for job " + task.taskId());
                return;
            }
            updateAcceptingTag();
        }
        log("Accepted job " + task.taskId() + " (" + tasks.size() + "/" + managerClientsN + " clients)");
        jobPool.submit(() -> jobStarter.start(task, request.inputS3Key()));
    }

    /** Writes the summary, tells the client, and forgets the job. Never throws. */
    void finishTask(TaskState task) {
        String key = Config.SUMMARIES_PREFIX + task.taskId() + ".jsonl";
        StringBuilder summary = new StringBuilder();
        for (OcrResult result : task.sortedResults()) {
            summary.append(Json.write(result)).append('\n');
        }
        for (int attempt = 1; attempt <= SUMMARY_ATTEMPTS; attempt++) {
            try {
                AwsClients.S3.putObject(PutObjectRequest.builder()
                                .bucket(Config.BUCKET).key(key).contentType("application/x-ndjson").build(),
                        RequestBody.fromString(summary.toString(), StandardCharsets.UTF_8));
                reply(task.replyQueueUrl(), TaskDone.success(task.taskId(), key));
                log("Job " + task.taskId() + " done: summary at " + key);
                forget(task);
                return;
            } catch (Exception e) {
                log("Could not upload summary for " + task.taskId() + " (attempt " + attempt + "): " + e);
                sleepQuietly(ERROR_BACKOFF_MS);
            }
        }
        failTask(task, "Could not upload the summary file");
    }

    /** Answers the client with an error and forgets the job. Results that still arrive for it are ignored. */
    void failTask(TaskState task, String error) {
        reply(task.replyQueueUrl(), TaskDone.failure(task.taskId(), error));
        log("Job " + task.taskId() + " failed: " + error);
        forget(task);
    }

    private void forget(TaskState task) {
        synchronized (lock) {
            tasks.remove(task.taskId());
            updateAcceptingTag();
        }
    }

    TaskState task(String taskId) {
        return tasks.get(taskId);
    }

    /** Never throws: called from the job pool and every 30s by the monitor. */
    /**
     * Every 30s: with jobs, make sure there are enough workers (replaces dead ones); with no jobs for
     * IDLE_WORKERS_TIMEOUT_MS, terminate the workers so they don't cost money while nobody needs them.
     * A job that arrives later simply launches new workers. Never throws.
     */
    private void monitorTick() {
        long now = System.currentTimeMillis();
        if (!tasks.isEmpty()) {
            lastBusy = now;
            scaleWorkers();
            return;
        }
        if (now - lastBusy >= IDLE_WORKERS_TIMEOUT_MS) {
            try {
                scaler.terminateIdle();
            } catch (Exception e) {
                log("Idle worker shutdown error: " + e);
            }
        }
    }

    void scaleWorkers() {
        if (local) {
            return;
        }
        try {
            scaler.scale(tasks.values());
        } catch (Exception e) {
            log("Worker scaling error: " + e);
        }
    }

    boolean isRunning() {
        return running;
    }

    private boolean isTerminating() {
        synchronized (lock) {
            return terminating;
        }
    }

    /** Accepting=true only while not terminating and below managerClientsN clients. Call holding lock. */
    private void updateAcceptingTag() {
        setAcceptingTag(!terminating && tasks.size() < managerClientsN);
    }

    private void setAcceptingTag(boolean accepting) {
        if (Boolean.valueOf(accepting).equals(acceptingTag)) {
            return;
        }
        if (!local) {
            try {
                Ec2Instances.setTag(managerId, Config.TAG_ACCEPTING, String.valueOf(accepting));
            } catch (Exception e) {
                // Left unchanged so the next job start/finish tries again.
                log("Could not update the " + Config.TAG_ACCEPTING + " tag: " + e);
                return;
            }
        }
        acceptingTag = accepting;
        log("Tag " + Config.TAG_ACCEPTING + "=" + accepting);
    }

    private static void reply(String replyQueueUrl, TaskDone done) {
        try {
            AwsClients.SQS.sendMessage(SendMessageRequest.builder()
                    .queueUrl(replyQueueUrl).messageBody(Json.write(done)).build());
        } catch (Exception e) {
            // The client may be gone (its reply queue deleted). Nothing else to do.
            log("Could not reply to " + replyQueueUrl + ": " + e);
        }
    }

    /** All jobs are answered: stop threads, terminate workers, delete queues, terminate this instance. */
    private void shutDown() {
        log("All jobs answered, shutting down");
        running = false;
        monitor.shutdownNow();
        jobPool.shutdown();
        resultPool.shutdown();
        try {
            // Result listeners notice running=false after their current long poll (at most 20s).
            resultPool.awaitTermination(Config.LONG_POLL_WAIT_SEC + 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!local) {
            scaler.terminateAll();
        }
        Queues.delete(requestsQueueUrl);
        Queues.delete(tasksQueueUrl);
        Queues.delete(resultsQueueUrl);
        log("Queues deleted");
        if (!local) {
            log("Terminating this instance");
            Ec2Instances.terminate(List.of(managerId));
        }
    }

    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void log(String message) {
        System.out.println("[manager " + Thread.currentThread().getName() + "] " + message);
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + "-" + count.incrementAndGet());
    }
}
