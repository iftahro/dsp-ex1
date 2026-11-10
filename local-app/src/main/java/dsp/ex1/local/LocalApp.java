package dsp.ex1.local;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Usage: java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
 *
 * Lives for one input file, then exits.
 */
public class LocalApp {

    public static void main(String[] args) {
        if (args.length < 4 || args.length > 5 || (args.length == 5 && !args[4].equals("terminate"))) {
            System.err.println("Usage: java -jar local-app.jar inputFileName outputFileName "
                    + "manager-clients-n worker-files-n [terminate]");
            System.exit(1);
        }
        Path inputFile = Path.of(args[0]);
        Path outputFile = Path.of(args[1]);
        int managerClientsN = Integer.parseInt(args[2]);
        int workerFilesN = Integer.parseInt(args[3]);
        boolean terminate = args.length == 5;

        String taskId = UUID.randomUUID().toString();

        // TODO 1. Create this app's own reply queue (Config.REPLY_QUEUE_PREFIX + taskId).
        // TODO 2. Find an available manager (EC2 tag Role=manager, serving < managerClientsN clients); start one if none.
        // TODO 3. Upload inputFile to S3 under Config.INPUTS_PREFIX.
        // TODO 4. Send ManagerRequest.newTask(...) to Config.APP_TO_MANAGER_QUEUE.
        // TODO 5. Long-poll the reply queue until a TaskDone arrives.
        // TODO 6. Download the summary from S3 and write the HTML to outputFile.
        // TODO 7. If terminate: send ManagerRequest.terminate().
        // TODO 8. Clean up: delete the reply queue (and optionally the input file on S3).
        throw new UnsupportedOperationException("LocalApp not implemented yet");
    }
}
