package dsp.ex1.worker;

/**
 * Runs on EC2 (tag Role=worker). Stateless loop:
 *   receive ImageTask -> download image -> OCR -> send OcrResult -> delete message.
 *
 * Never crashes on a bad image: it sends OcrResult.failure(...) and moves on.
 * If the worker dies mid-message, the message is not deleted, so after the visibility
 * timeout SQS hands it to another worker.
 */
public class Worker {

    private final OcrService ocr = new OcrService();

    public static void main(String[] args) {
        new Worker().run();
    }

    void run() {
        // TODO: loop forever:
        //   - long-poll MANAGER_TO_WORKERS_QUEUE (1 message, visibility = Config.WORKER_VISIBILITY_TIMEOUT_SEC)
        //   - parse ImageTask, download the image to a temp file
        //   - text = ocr.extractText(file)  (catch everything -> OcrResult.failure)
        //   - send OcrResult to WORKERS_TO_MANAGER_QUEUE
        //   - delete the message, delete the temp file
        throw new UnsupportedOperationException("Worker not implemented yet");
    }
}
