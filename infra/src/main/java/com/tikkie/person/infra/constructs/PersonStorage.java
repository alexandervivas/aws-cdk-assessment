package com.tikkie.person.infra.constructs;

import com.tikkie.person.infra.EnvironmentConfig;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.Billing;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.StreamViewType;
import software.amazon.awscdk.services.dynamodb.TableEncryptionV2;
import software.amazon.awscdk.services.dynamodb.TablePropsV2;
import software.amazon.awscdk.services.dynamodb.TableV2;
import software.amazon.awscdk.services.kms.Key;
import software.constructs.Construct;

public final class PersonStorage extends Construct {

    private final Key encryptionKey;
    private final TableV2 personsTable;
    private final TableV2 idempotencyKeysTable;

    public PersonStorage(Construct scope, String id, EnvironmentConfig config) {
        super(scope, id);
        this.encryptionKey = Key.Builder.create(this, "TablesKey")
                .description("Encrypts the Person Service tables and the event dead-letter queue")
                .enableKeyRotation(true)
                .removalPolicy(config.removalPolicy())
                .build();
        this.personsTable = new TableV2(this, "Persons", commonTableProps(config, encryptionKey)
                .partitionKey(stringKey("personId"))
                .dynamoStream(StreamViewType.NEW_IMAGE)
                .build());
        this.idempotencyKeysTable = new TableV2(this, "IdempotencyKeys", commonTableProps(config, encryptionKey)
                .partitionKey(stringKey("idempotencyKey"))
                .timeToLiveAttribute("expiresAt")
                .build());
    }

    public Key encryptionKey() {
        return encryptionKey;
    }

    public TableV2 personsTable() {
        return personsTable;
    }

    public TableV2 idempotencyKeysTable() {
        return idempotencyKeysTable;
    }

    private static TablePropsV2.Builder commonTableProps(EnvironmentConfig config, Key encryptionKey) {
        return TablePropsV2.builder()
                .billing(Billing.onDemand())
                .encryption(TableEncryptionV2.customerManagedKey(encryptionKey))
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(config.pointInTimeRecovery())
                        .build())
                .deletionProtection(config.deletionProtection())
                .removalPolicy(config.removalPolicy());
    }

    private static Attribute stringKey(String name) {
        return Attribute.builder().name(name).type(AttributeType.STRING).build();
    }
}
