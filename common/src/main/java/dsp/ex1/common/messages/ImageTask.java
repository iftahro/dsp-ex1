package dsp.ex1.common.messages;

/**
 * Manager -> Workers (queue: MANAGER_TO_WORKERS_QUEUE). One message per URL.
 * index is the URL's line number in the input file: it keeps the output order and lets the
 * manager ignore duplicate results (SQS delivers at least once).
 */
public record ImageTask(String taskId, int index, String imageUrl) {}
