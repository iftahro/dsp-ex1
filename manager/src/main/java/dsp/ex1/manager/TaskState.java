package dsp.ex1.manager;

import dsp.ex1.common.messages.OcrResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Progress of one local app's input file. The manager is the only one who counts results,
 * so workers never need shared state.
 */
public class TaskState {

    private final String taskId;
    private final String replyQueueUrl;
    private final int expected;
    private final Map<Integer, OcrResult> results = new ConcurrentHashMap<>();
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public TaskState(String taskId, String replyQueueUrl, int expected) {
        this.taskId = taskId;
        this.replyQueueUrl = replyQueueUrl;
        this.expected = expected;
    }

    /**
     * Records a result; a duplicate for the same index is ignored.
     * Returns true exactly once: for the thread whose result completed the task.
     */
    public boolean addResult(OcrResult result) {
        results.putIfAbsent(result.index(), result);
        return results.size() == expected && finished.compareAndSet(false, true);
    }

    public String taskId() {
        return taskId;
    }

    public String replyQueueUrl() {
        return replyQueueUrl;
    }

    public int expected() {
        return expected;
    }

    public Map<Integer, OcrResult> results() {
        return results;
    }
}
