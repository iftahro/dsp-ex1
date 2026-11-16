package dsp.ex1.manager;

import dsp.ex1.common.messages.OcrResult;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Progress of one local app's input file. The manager is the only one who counts results,
 * so workers never need shared state.
 *
 * Created as soon as the request is accepted; expected is set once the input file has been read,
 * before any ImageTask is sent, so a result can never arrive for a task whose size is unknown.
 */
public class TaskState {

    private final String taskId;
    private final String replyQueueUrl;
    private final int workerFilesN;
    private volatile int expected = -1;
    private final Map<Integer, OcrResult> results = new ConcurrentHashMap<>();
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public TaskState(String taskId, String replyQueueUrl, int workerFilesN) {
        this.taskId = taskId;
        this.replyQueueUrl = replyQueueUrl;
        this.workerFilesN = Math.max(1, workerFilesN);
    }

    public void setExpected(int expected) {
        this.expected = expected;
    }

    /**
     * Records a result; a duplicate for the same index is ignored (SQS delivers at least once).
     * Returns true exactly once: for the thread whose result completed the task.
     */
    public boolean addResult(OcrResult result) {
        results.putIfAbsent(result.index(), result);
        return isComplete() && finished.compareAndSet(false, true);
    }

    /** For an input with no URLs: true exactly once, like addResult. */
    public boolean completeIfEmpty() {
        return isComplete() && finished.compareAndSet(false, true);
    }

    private boolean isComplete() {
        int target = expected;
        return target >= 0 && results.size() >= target;
    }

    /** Workers this task still needs: one per workerFilesN images not processed yet. */
    public int workersNeeded() {
        int target = expected;
        if (target < 0) {
            return 0;
        }
        int remaining = Math.max(0, target - results.size());
        return (remaining + workerFilesN - 1) / workerFilesN;
    }

    /** All results in input-file order. */
    public List<OcrResult> sortedResults() {
        return results.values().stream().sorted(Comparator.comparingInt(OcrResult::index)).toList();
    }

    public String taskId() {
        return taskId;
    }

    public String replyQueueUrl() {
        return replyQueueUrl;
    }
}
