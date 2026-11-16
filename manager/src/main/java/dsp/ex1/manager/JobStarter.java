package dsp.ex1.manager;

import dsp.ex1.common.AwsClients;
import dsp.ex1.common.Config;
import dsp.ex1.common.Json;
import dsp.ex1.common.Queues;
import dsp.ex1.common.messages.ImageTask;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sets up one new job (runs on the job pool, so several clients are set up in parallel):
 * download the input file, send one ImageTask per URL, then ask for enough workers.
 */
class JobStarter {

    private final Manager manager;
    private final String tasksQueueUrl;

    JobStarter(Manager manager, String tasksQueueUrl) {
        this.manager = manager;
        this.tasksQueueUrl = tasksQueueUrl;
    }

    void start(TaskState task, String inputS3Key) {
        List<String> urls;
        try {
            urls = readUrls(inputS3Key);
        } catch (Exception e) {
            manager.failTask(task, "Cannot read input file s3://" + Config.BUCKET + "/" + inputS3Key + ": "
                    + e.getClass().getSimpleName());
            return;
        }

        // Size first, then send: results can only arrive after this, so the task always knows its total.
        task.setExpected(urls.size());
        if (urls.isEmpty()) {
            if (task.completeIfEmpty()) {
                manager.finishTask(task);
            }
            return;
        }

        List<String> bodies = new ArrayList<>(urls.size());
        for (int i = 0; i < urls.size(); i++) {
            bodies.add(Json.write(new ImageTask(task.taskId(), i, urls.get(i))));
        }
        try {
            Queues.sendAll(tasksQueueUrl, bodies);
        } catch (Exception e) {
            // Some ImageTasks may already be out; their results are ignored once the task is gone.
            manager.failTask(task, "Could not queue the images: " + e.getClass().getSimpleName());
            return;
        }
        Manager.log("Task " + task.taskId() + ": queued " + urls.size() + " images");
        manager.scaleWorkers();
    }

    /** Non-blank lines of the input file, in order. */
    private static List<String> readUrls(String inputS3Key) {
        String content = AwsClients.S3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(Config.BUCKET).key(inputS3Key).build()).asString(StandardCharsets.UTF_8);
        return content.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }
}
