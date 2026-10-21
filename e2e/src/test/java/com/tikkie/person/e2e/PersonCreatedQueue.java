package com.tikkie.person.e2e;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

final class PersonCreatedQueue implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LONG_POLL_SECONDS = 20;
    private static final int MAX_MESSAGES = 10;

    private final String queueUrl;
    private final SqsClient sqs;

    PersonCreatedQueue(String queueUrl, Region region) {
        this.queueUrl = queueUrl;
        this.sqs = SqsClient.builder()
                .region(region)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    // Other tests' events stay in the queue and reappear after their visibility timeout; only the match is deleted.
    Optional<String> awaitEventFor(String personId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (Message message : receive(deadline)) {
                if (personId.equals(personIdOf(message.body()))) {
                    sqs.deleteMessage(delete -> delete.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                    return Optional.of(message.body());
                }
            }
        }
        return Optional.empty();
    }

    private Iterable<Message> receive(Instant deadline) {
        long secondsLeft = Duration.between(Instant.now(), deadline).toSeconds();
        int waitSeconds = (int) Math.clamp(secondsLeft, 1, MAX_LONG_POLL_SECONDS);
        return sqs.receiveMessage(receive -> receive
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(MAX_MESSAGES)
                        .waitTimeSeconds(waitSeconds)
                        // Unmatched messages may belong to another run; hide them only briefly.
                        .visibilityTimeout(1))
                .messages();
    }

    private static String personIdOf(String body) {
        try {
            return JSON.readTree(body).at("/detail/data/personId").asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Override
    public void close() {
        sqs.close();
    }
}
