package dsp.ex1.common;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Reads this machine's EC2 instance id from the instance metadata service (IMDSv2). Works only on EC2. */
public final class InstanceMetadata {

    private InstanceMetadata() {}

    private static final String BASE = "http://169.254.169.254/latest/";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    public static String instanceId() throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        String token = http.send(HttpRequest.newBuilder(URI.create(BASE + "api/token"))
                        .timeout(TIMEOUT)
                        .header("X-aws-ec2-metadata-token-ttl-seconds", "300")
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()).body();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(BASE + "meta-data/instance-id"))
                        .timeout(TIMEOUT)
                        .header("X-aws-ec2-metadata-token", token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Instance metadata returned HTTP " + response.statusCode());
        }
        return response.body().trim();
    }
}
