package dsp.ex1.common.messages;

/**
 * Local App -> Manager (queue: APP_TO_MANAGER_QUEUE).
 * type NEW_TASK: inputS3Key, replyQueueUrl and workerFilesN are set.
 * type TERMINATE: only type is needed.
 */
public record ManagerRequest(
        Type type,
        String taskId,
        String inputS3Key,
        String replyQueueUrl,
        int workerFilesN) {

    public enum Type { NEW_TASK, TERMINATE }

    public static ManagerRequest newTask(String taskId, String inputS3Key, String replyQueueUrl, int workerFilesN) {
        return new ManagerRequest(Type.NEW_TASK, taskId, inputS3Key, replyQueueUrl, workerFilesN);
    }

    public static ManagerRequest terminate() {
        return new ManagerRequest(Type.TERMINATE, null, null, null, 0);
    }
}
