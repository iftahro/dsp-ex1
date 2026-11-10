package dsp.ex1.common.messages;

/** Manager -> Local App (queue: that app's own reply queue). Points at the summary file on S3. */
public record TaskDone(String taskId, String summaryS3Key) {}
