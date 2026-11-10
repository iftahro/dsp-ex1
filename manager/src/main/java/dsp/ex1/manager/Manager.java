package dsp.ex1.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs on EC2 (tag Role=manager). Long-lived: serves many local apps until it gets TERMINATE.
 *
 * Planned threads:
 *  - request listener: reads APP_TO_MANAGER_QUEUE, hands each NEW_TASK to a thread pool
 *    (download input, send one ImageTask per URL, scale workers).
 *  - result listener(s): read WORKERS_TO_MANAGER_QUEUE, record results in TaskState,
 *    and when a task completes build the summary, upload it, and send TaskDone.
 */
public class Manager {

    /** Every task in progress, by taskId. Touched by several threads. */
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        new Manager().run();
    }

    void run() {
        // TODO: make sure the shared queues exist (create if missing).
        // TODO: start the request listener and result listener threads.
        // TODO: on TERMINATE: stop accepting new tasks, wait until `tasks` is empty,
        //       terminate all worker instances, then terminate this instance.
        throw new UnsupportedOperationException("Manager not implemented yet");
    }
}
