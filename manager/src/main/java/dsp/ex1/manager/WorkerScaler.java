package dsp.ex1.manager;

import dsp.ex1.common.Config;
import dsp.ex1.common.Ec2Instances;
import dsp.ex1.common.UserData;
import software.amazon.awssdk.services.ec2.model.Instance;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Starts and stops this manager's workers. Called when a job is queued and by the monitor every 30s,
 * so dead workers are replaced even when no new client arrives, and idle workers are stopped after 5 minutes.
 *
 * Rule (spec): m workers needed, k running -> launch m - k, never above Config.MAX_INSTANCES (9, the lab limit).
 * m is summed over all active jobs, each with its own worker-files-n: ceil(images left / n).
 * Workers are never assigned messages; they all pull from the same queue.
 */
class WorkerScaler {

    private final String managerId;
    private final Map<String, String> workerTags;

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
        int running = myWorkers().size();
        if (running >= needed) {
            return;
        }
        int room = Config.MAX_INSTANCES - Ec2Instances.countActive();
        int toLaunch = Math.min(needed - running, room);
        if (toLaunch <= 0) {
            Manager.log("Need " + needed + " workers, have " + running + ", but the instance limit is reached");
            return;
        }
        List<String> launched = Ec2Instances.launch(toLaunch, UserData.worker(managerId), workerTags);
        Manager.log("Need " + needed + " workers, had " + running + ", launched " + launched.size() + " " + launched);
    }

    /** Terminates this manager's workers, if there are any. Logs only when it actually terminated some. */
    synchronized void terminateIdle() {
        List<String> ids = myWorkers().stream().map(Instance::instanceId).toList();
        if (!ids.isEmpty()) {
            Ec2Instances.terminate(ids);
            Manager.log("No jobs for 5 minutes: terminated " + ids.size() + " idle workers " + ids);
        }
    }

    synchronized void terminateAll() {
        List<String> ids = myWorkers().stream().map(Instance::instanceId).toList();
        Ec2Instances.terminate(ids);
        Manager.log("Terminated " + ids.size() + " workers " + ids);
    }

    private List<Instance> myWorkers() {
        return Ec2Instances.findActive(workerTags);
    }
}
