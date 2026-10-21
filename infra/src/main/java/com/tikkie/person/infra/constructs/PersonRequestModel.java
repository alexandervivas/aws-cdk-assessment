package com.tikkie.person.infra.constructs;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import software.amazon.awscdk.services.apigateway.JsonSchema;
import software.amazon.awscdk.services.apigateway.JsonSchemaType;
import software.amazon.awscdk.services.apigateway.JsonSchemaVersion;

// Layer 1 of the validation rules (docs/plan.md §5.3). The domain value objects remain the authority.
final class PersonRequestModel {

    static final String CONTENT_TYPE = "application/json";

    private PersonRequestModel() {
    }

    static JsonSchema schema() {
        return JsonSchema.builder()
                .schema(JsonSchemaVersion.DRAFT4)
                .title("CreatePersonRequest")
                .type(JsonSchemaType.OBJECT)
                .additionalProperties(false)
                .required(List.of("firstName", "lastName", "phoneNumber", "address"))
                .properties(properties(Map.of(
                        "firstName", text(100),
                        "lastName", text(100),
                        "phoneNumber", text(32),
                        "address", address())))
                .build();
    }

    private static JsonSchema address() {
        return JsonSchema.builder()
                .type(JsonSchemaType.OBJECT)
                .additionalProperties(false)
                .required(List.of("street", "houseNumber", "postalCode", "city", "countryCode"))
                .properties(properties(Map.of(
                        "street", text(200),
                        "houseNumber", text(10),
                        "houseNumberAddition", text(10),
                        "postalCode", text(20),
                        "city", text(100),
                        "countryCode", JsonSchema.builder().type(JsonSchemaType.STRING).pattern("^[A-Z]{2}$").build())))
                .build();
    }

    // Map.of iteration order can change between JVM runs. A sorted map keeps the synthesised schema, and with it
    // the API deployment's logical id, the same from one synth to the next.
    private static Map<String, JsonSchema> properties(Map<String, JsonSchema> properties) {
        return new TreeMap<>(properties);
    }

    private static JsonSchema text(int maxLength) {
        return JsonSchema.builder().type(JsonSchemaType.STRING).maxLength(maxLength).build();
    }
}
