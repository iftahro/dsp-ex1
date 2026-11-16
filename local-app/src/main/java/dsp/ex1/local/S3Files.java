package dsp.ex1.local;

import dsp.ex1.common.AwsClients;
import dsp.ex1.common.Config;
import dsp.ex1.common.Json;
import dsp.ex1.common.messages.OcrResult;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The local app's S3 work: upload the input, read the summary and texts, delete the job's files. */
final class S3Files {

    private S3Files() {}

    /** Text downloads are network-bound, so a few threads finish a large job much faster. */
    private static final int DOWNLOAD_THREADS = 10;
    /** S3 DeleteObjects accepts at most 1000 keys per call. */
    private static final int DELETE_BATCH = 1000;

    static void upload(String key, Path file) {
        AwsClients.S3.putObject(PutObjectRequest.builder()
                .bucket(Config.BUCKET).key(key).contentType("text/plain; charset=utf-8").build(), file);
    }

    /** The summary: one OcrResult JSON per line, already ordered by index. */
    static List<OcrResult> readSummary(String key) {
        return read(key).lines()
                .filter(line -> !line.isBlank())
                .map(line -> Json.read(line, OcrResult.class))
                .toList();
    }

    /** OCR text per index for every successful result. A text that can't be read becomes an error message. */
    static Map<Integer, String> readTexts(List<OcrResult> results) throws InterruptedException {
        Map<Integer, String> texts = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS);
        try {
            List<Future<?>> downloads = new ArrayList<>();
            for (OcrResult result : results) {
                if (result.failed()) {
                    continue;
                }
                downloads.add(pool.submit(() -> {
                    try {
                        texts.put(result.index(), read(result.textS3Key()));
                    } catch (Exception e) {
                        texts.put(result.index(), "[could not download the OCR text: " + e.getClass().getSimpleName() + "]");
                    }
                }));
            }
            for (Future<?> download : downloads) {
                try {
                    download.get();
                } catch (ExecutionException e) {
                    // Not reachable: each task catches its own exceptions.
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return texts;
    }

    /** Deletes the input, the summary and every results/<taskId>/ text. */
    static void deleteJobFiles(String taskId, String inputKey) {
        List<String> keys = new ArrayList<>();
        keys.add(inputKey);
        keys.add(Config.SUMMARIES_PREFIX + taskId + ".jsonl");
        AwsClients.S3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                        .bucket(Config.BUCKET).prefix(Config.RESULTS_PREFIX + taskId + "/").build())
                .contents().stream().map(S3Object::key).forEach(keys::add);

        for (int start = 0; start < keys.size(); start += DELETE_BATCH) {
            List<ObjectIdentifier> batch = keys.subList(start, Math.min(start + DELETE_BATCH, keys.size())).stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList();
            // Deleting a key that doesn't exist (e.g. no summary because the job failed) is not an error.
            AwsClients.S3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(Config.BUCKET).delete(Delete.builder().objects(batch).quiet(true).build()).build());
        }
    }

    private static String read(String key) {
        return AwsClients.S3.getObjectAsBytes(GetObjectRequest.builder().bucket(Config.BUCKET).key(key).build())
                .asString(StandardCharsets.UTF_8);
    }
}
