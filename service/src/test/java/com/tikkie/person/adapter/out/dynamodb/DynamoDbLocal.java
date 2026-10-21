package com.tikkie.person.adapter.out.dynamodb;

import java.net.URI;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

final class DynamoDbLocal {

    private static final int PORT = 8000;

    // One container for the whole JVM; Testcontainers' reaper removes it when the JVM exits.
    private static final GenericContainer<?> CONTAINER =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:3.3.2")).withExposedPorts(PORT);

    static {
        CONTAINER.start();
    }

    record Tables(String persons, String idempotencyKeys) {
    }

    private DynamoDbLocal() {
    }

    static DynamoDbClient client() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(PORT)))
                .region(Region.EU_WEST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    static Tables createTables(DynamoDbClient client) {
        String suffix = UUID.randomUUID().toString();
        Tables tables = new Tables("persons-" + suffix, "idempotency-keys-" + suffix);
        createTable(client, tables.persons(), "personId");
        createTable(client, tables.idempotencyKeys(), "idempotencyKey");
        client.updateTimeToLive(ttl -> ttl.tableName(tables.idempotencyKeys())
                .timeToLiveSpecification(spec -> spec.attributeName("expiresAt").enabled(true)));
        return tables;
    }

    private static void createTable(DynamoDbClient client, String name, String partitionKey) {
        client.createTable(table -> table.tableName(name)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName(partitionKey).attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName(partitionKey).keyType(KeyType.HASH).build()));
    }
}
