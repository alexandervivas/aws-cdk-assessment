package com.tikkie.person.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;

final class SigV4Client {

    private static final String SERVICE = "execute-api";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // HTTP/1.1, because HTTP/2 lower-cases every header name and the header-casing test would prove nothing.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();
    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();
    private final Region region;
    private final AwsCredentialsProvider credentials;

    SigV4Client(Region region) {
        this(region, DefaultCredentialsProvider.builder().build());
    }

    SigV4Client(Region region, AwsCredentialsProvider credentials) {
        this.region = region;
        this.credentials = credentials;
    }

    HttpResponse<String> post(URI uri, Map<String, String> headers, String body) {
        SdkHttpRequest.Builder unsigned = SdkHttpRequest.builder().method(SdkHttpMethod.POST).uri(uri);
        headers.forEach(unsigned::putHeader);
        SignedRequest signed = signer.sign(request -> request
                .identity(credentials.resolveCredentials())
                .request(unsigned.build())
                .payload(ContentStreamProvider.fromUtf8String(body))
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, SERVICE)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region.id()));
        return send(uri, signed.request(), body);
    }

    private HttpResponse<String> send(URI uri, SdkHttpRequest signed, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8));
        // The JDK client sets Host itself and refuses it as a user header; the signed value is the same.
        signed.forEachHeader((name, values) -> {
            if (!name.equalsIgnoreCase("Host")) {
                values.forEach(value -> request.header(name, value));
            }
        });
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the API", e);
        }
    }
}
