package dsp.ex1.manager;

import dsp.ex1.common.AwsClients;
import dsp.ex1.common.Config;
import dsp.ex1.common.Json;
import dsp.ex1.common.Queues;
import dsp.ex1.common.messages.OcrResult;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

/**
 * One result-listener thread (the manager runs several). Reads up to 10 OcrResults per poll,
 * records each in its task, and finishes a task when its last result arrives.
 */
class ResultCollector implements Runnable {

    private static final long ERROR_BACKOFF_MS = 5_000;

    private final Manager manager;
    private final String resultsQueueUrl;

    ResultCollector(Manager manager, String resultsQueueUrl) {
        this.manager = manager;
        this.resultsQueueUrl = resultsQueueUrl;
    }

    @Override
    public void run() {
        while (manager.isRunning()) {
            try {
                List<Message> messages = AwsClients.SQS.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(resultsQueueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(Config.LONG_POLL_WAIT_SEC)
                        .build()).messages();
                for (Message message : messages) {
                    record(message);
                }
                // Deleted only after recording: if this thread dies first, the results come back.
                Queues.deleteAll(resultsQueueUrl, messages);
            } catch (Exception e) {
                Manager.log("Result listener error: " + e);
                Manager.sleepQuietly(ERROR_BACKOFF_MS);
            }
        }
    }

    private void record(Message message) {
        OcrResult result;
        try {
            result = Json.read(message.body(), OcrResult.class);
        } catch (IllegalArgumentException e) {
            Manager.log("Dropping malformed result: " + message.body());
            return;
        }
        TaskState task = manager.task(result.taskId());
        if (task == null) {
            // Late duplicate for a finished task, or a task that failed: nothing to update.
            return;
        }
        if (task.addResult(result)) {
            manager.finishTask(task);
        }
    }
}
