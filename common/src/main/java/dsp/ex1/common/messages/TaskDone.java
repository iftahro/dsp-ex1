package dsp.ex1.common.messages;

/**
 * Manager -> Local App (queue: that app's own reply queue).
 * On success, summaryS3Key points at the summary file on S3; on failure (bad input file, manager
 * terminating), error holds a short description. Exactly one of the two is set.
 */
public record TaskDone(String taskId, String summaryS3Key, String error) {

    public static TaskDone success(String taskId, String summaryS3Key) {
        return new TaskDone(taskId, summaryS3Key, null);
    }

    public static TaskDone failure(String taskId, String error) {
        return new TaskDone(taskId, null, error);
    }

    /** Not named isError(): Jackson would turn it into an "error" property and overwrite the real field. */
    public boolean failed() {
        return error != null;
    }
}
