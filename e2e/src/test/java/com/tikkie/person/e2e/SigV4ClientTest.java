package com.tikkie.person.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

class SigV4ClientTest {

    private static final String BODY = "{\"firstName\":\"Fenna\"}";

    @Test
    void sendsASignedRequestWithTheHeaderNamesAsGiven() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> answerOnce(server));
            SigV4Client client = new SigV4Client(Region.EU_WEST_1, StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("EXAMPLEACCESSKEY", "example-secret-key")));

            HttpResponse<String> response = client.post(
                    URI.create("http://localhost:" + server.getLocalPort() + "/dev/person"),
                    Map.of("Content-Type", "application/json", "idempotency-key", "a-key"), BODY);

            String request = received.get();
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(request).startsWith("POST /dev/person HTTP/1.1\r\n")
                    .contains("\r\nidempotency-key: a-key\r\n")
                    .contains("\r\nAuthorization: AWS4-HMAC-SHA256 Credential=EXAMPLEACCESSKEY/")
                    .contains("/eu-west-1/execute-api/aws4_request, SignedHeaders=")
                    .containsIgnoringCase("\r\nX-Amz-Date: ")
                    .endsWith("\r\n\r\n" + BODY);
        }
    }

    private static String answerOnce(ServerSocket server) {
        try (Socket socket = server.accept()) {
            String request = readRequest(socket.getInputStream());
            socket.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n".getBytes(UTF_8));
            return request;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (!bytes.toString(UTF_8).endsWith(BODY)) {
            int next = in.read();
            if (next < 0) {
                break;
            }
            bytes.write(next);
        }
        return bytes.toString(UTF_8);
    }
}
