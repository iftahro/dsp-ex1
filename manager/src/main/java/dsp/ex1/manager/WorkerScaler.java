package dsp.ex1.manager;

import dsp.ex1.common.Config;
import dsp.ex1.common.Ec2Instances;
import dsp.ex1.common.UserData;
import software.amazon.awssdk.services.ec2.model.Instance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts and stops this manager's workers. Called when a job is queued and by the monitor every 30s,
 * so dead workers are replaced even when no new client arrives, and idle workers are stopped after 5 minutes.
 *
 * Rule (spec): m workers needed, k running -> launch m - k, never above Config.MAX_INSTANCES (9, the lab limit).
 * m is summed over all active jobs, each with its own worker-files-n: ceil(images left / n).
 * Workers are never assigned messages; they all pull from the same queue.
 *
 * EC2's DescribeInstances is eventually consistent: an instance launched a few seconds ago is often not
 * listed yet. So the scaler also remembers what it launched itself in the last RECENT_MS and counts those
 * too; otherwise two scale calls close together both see 0 workers and launch twice.
 */
class WorkerScaler {

    private static final long RECENT_MS = 3 * 60 * 1000;

    private final String managerId;
    private final Map<String, String> workerTags;
    /** Workers this manager launched recently: instance id -> launch time. Guarded by this. */
    private final Map<String, Long> recentLaunches = new HashMap<>();

    WorkerScaler(String managerId) {
        this.managerId = managerId;
        this.workerTags = Map.of(Config.TAG_ROLE, Config.ROLE_WORKER, Config.TAG_MANAGER_ID, managerId);
    }

    /** synchronized: the job pool and the monitor must not both launch for the same shortfall. */
    synchronized void scale(Collection<TaskState> tasks) {
        int needed = tasks.stream().mapToInt(TaskState::workersNeeded).sum();
        if (needed == 0) {
            return;
        }
        forgetOldLaunches();
        int running = myWorkerIds().size();
        if (running >= needed) {
            return;
        }
        Set<String> active = Ec2Instances.activeIds();
        long notListedYet = recentLaunches.keySet().stream().filter(id -> !active.contains(id)).count();
        int room = Config.MAX_INSTANCES - active.size() - (int) notListedYet;
        int toLaunch = Math.min(needed - running, room);
        if (toLaunch <= 0) {
            Manager.log("Need " + needed + " workers, have " + running + ", but the instance limit is reached");
            return;
        }
        List<String> launched = Ec2Instances.launch(toLaunch, UserData.worker(managerId), workerTags);
        long now = System.currentTimeMillis();
        launched.forEach(id -> recentLaunches.put(id, now));
        Manager.log("Need " + needed + " workers, had " + running + ", launched " + launched.size() + " " + launched);
    }

    /** Terminates this manager's workers, if there are any. Logs only when it actually terminated some. */
    synchronized void terminateIdle() {
        List<String> ids = new ArrayList<>(myWorkerIds());
        if (!ids.isEmpty()) {
            Ec2Instances.terminate(ids);
            recentLaunches.clear();
            Manager.log("No jobs for 5 minutes: terminated " + ids.size() + " idle workers " + ids);
        }
    }

    synchronized void terminateAll() {
        List<String> ids = new ArrayList<>(myWorkerIds());
        Ec2Instances.terminate(ids);
        recentLaunches.clear();
        Manager.log("Terminated " + ids.size() + " workers " + ids);
    }

    /** Workers EC2 lists for this manager, plus the ones it launched recently that may not be listed yet. */
    private Set<String> myWorkerIds() {
        Set<String> ids = new HashSet<>(recentLaunches.keySet());
        Ec2Instances.findActive(workerTags).stream().map(Instance::instanceId).forEach(ids::add);
        return ids;
    }

    private void forgetOldLaunches() {
        long cutoff = System.currentTimeMillis() - RECENT_MS;
        recentLaunches.values().removeIf(launchedAt -> launchedAt < cutoff);
    }
}
