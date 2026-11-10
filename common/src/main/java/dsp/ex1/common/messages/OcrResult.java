package dsp.ex1.common.messages;

/**
 * Worker -> Manager (queue: WORKERS_TO_MANAGER_QUEUE).
 * Exactly one of text / error is set.
 */
public record OcrResult(String taskId, int index, String imageUrl, String text, String error) {

    public static OcrResult success(ImageTask task, String text) {
        return new OcrResult(task.taskId(), task.index(), task.imageUrl(), text, null);
    }

    public static OcrResult failure(ImageTask task, String error) {
        return new OcrResult(task.taskId(), task.index(), task.imageUrl(), null, error);
    }

    public boolean isError() {
        return error != null;
    }
}
