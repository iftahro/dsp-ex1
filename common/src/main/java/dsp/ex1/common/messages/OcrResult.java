package dsp.ex1.common.messages;

/**
 * Worker -> Manager (queue: WORKERS_TO_MANAGER_QUEUE).
 * On success, textS3Key points at the extracted text on S3; on failure, error holds a short description.
 * Exactly one of textS3Key / error is set.
 */
public record OcrResult(String taskId, int index, String imageUrl, String textS3Key, String error) {

    public static OcrResult success(ImageTask task, String textS3Key) {
        return new OcrResult(task.taskId(), task.index(), task.imageUrl(), textS3Key, null);
    }

    public static OcrResult failure(ImageTask task, String error) {
        return new OcrResult(task.taskId(), task.index(), task.imageUrl(), null, error);
    }

    /**
     * Deliberately not named isError(): Jackson treats is*() methods as JSON properties, and "isError"
     * would overwrite the real "error" field with true/false.
     */
    public boolean failed() {
        return error != null;
    }
}
