package dsp.ex1.common;

import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** SQS helpers: create, delete, and batched send/delete (SQS allows at most 10 entries per batch call). */
public final class Queues {

    private Queues() {}

    private static final int BATCH_SIZE = 10;
    private static final int BATCH_ATTEMPTS = 3;

    /** Returns the queue URL. Safe to call again for an existing queue with the same name. */
    public static String create(String name) {
        return AwsClients.SQS.createQueue(CreateQueueRequest.builder().queueName(name).build()).queueUrl();
    }

    public static void delete(String queueUrl) {
        try {
            AwsClients.SQS.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
        } catch (QueueDoesNotExistException e) {
            // Already gone: nothing to do.
        }
    }

    /** Sends all bodies in batches of 10, retrying entries SQS rejected. Throws if some still fail. */
    public static void sendAll(String queueUrl, List<String> bodies) {
        for (int start = 0; start < bodies.size(); start += BATCH_SIZE) {
            List<SendMessageBatchRequestEntry> entries = new ArrayList<>();
            for (int i = start; i < Math.min(start + BATCH_SIZE, bodies.size()); i++) {
                entries.add(SendMessageBatchRequestEntry.builder().id(String.valueOf(i)).messageBody(bodies.get(i)).build());
            }
            for (int attempt = 1; !entries.isEmpty(); attempt++) {
                List<BatchResultErrorEntry> failed = AwsClients.SQS.sendMessageBatch(SendMessageBatchRequest.builder()
                        .queueUrl(queueUrl).entries(entries).build()).failed();
                entries = retryable(entries, failed, SendMessageBatchRequestEntry::id, attempt);
            }
        }
    }

    /** Deletes messages in batches of 10, retrying entries SQS rejected. Throws if some still fail. */
    public static void deleteAll(String queueUrl, List<Message> messages) {
        for (int start = 0; start < messages.size(); start += BATCH_SIZE) {
            List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>();
            for (int i = start; i < Math.min(start + BATCH_SIZE, messages.size()); i++) {
                entries.add(DeleteMessageBatchRequestEntry.builder()
                        .id(String.valueOf(i)).receiptHandle(messages.get(i).receiptHandle()).build());
            }
            for (int attempt = 1; !entries.isEmpty(); attempt++) {
                List<BatchResultErrorEntry> failed = AwsClients.SQS.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                        .queueUrl(queueUrl).entries(entries).build()).failed();
                entries = retryable(entries, failed, DeleteMessageBatchRequestEntry::id, attempt);
            }
        }
    }

    /** The entries to try again, or an exception once attempts run out. */
    private static <T> List<T> retryable(List<T> sent, List<BatchResultErrorEntry> failed,
                                         Function<T, String> idOf, int attempt) {
        if (failed.isEmpty()) {
            return List.of();
        }
        if (attempt >= BATCH_ATTEMPTS) {
            throw new IllegalStateException("SQS batch entries failed after " + attempt + " attempts: "
                    + failed.get(0).code() + " " + failed.get(0).message());
        }
        List<String> failedIds = failed.stream().map(BatchResultErrorEntry::id).toList();
        return sent.stream().filter(e -> failedIds.contains(idOf.apply(e))).toList();
    }
}
