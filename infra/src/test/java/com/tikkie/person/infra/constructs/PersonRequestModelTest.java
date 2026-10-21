package com.tikkie.person.infra.constructs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.apigateway.Model;
import software.amazon.awscdk.services.apigateway.RestApi;

class PersonRequestModelTest {

    @Test
    void modelsTheRequestBodyAsADraft4JsonSchema() {
        Stack stack = PersonFunctionTemplates.stack();
        Model.Builder.create(stack, "Model")
                .restApi(RestApi.fromRestApiId(stack, "Api", "stub-api-id"))
                .contentType(PersonRequestModel.CONTENT_TYPE)
                .schema(PersonRequestModel.schema())
                .build();

        Template.fromStack(stack).hasResourceProperties("AWS::ApiGateway::Model", Map.of(
                "ContentType", "application/json",
                "Schema", expectedSchema()));
    }

    @Test
    void listsPropertiesInSortedOrderAtBothLevels() {
        Map<String, ?> properties = PersonRequestModel.schema().getProperties();
        Map<String, ?> addressProperties = PersonRequestModel.schema().getProperties().get("address").getProperties();

        assertThat(properties.keySet()).containsExactly("address", "firstName", "lastName", "phoneNumber");
        assertThat(addressProperties.keySet())
                .containsExactly("city", "countryCode", "houseNumber", "houseNumberAddition", "postalCode", "street");
    }

    private static Map<String, Object> expectedSchema() {
        return Map.of(
                "$schema", "http://json-schema.org/draft-04/schema#",
                "title", "CreatePersonRequest",
                "type", "object",
                "additionalProperties", false,
                "required", List.of("firstName", "lastName", "phoneNumber", "address"),
                "properties", Map.of(
                        "firstName", text(100),
                        "lastName", text(100),
                        "phoneNumber", text(32),
                        "address", expectedAddressSchema()));
    }

    private static Map<String, Object> expectedAddressSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("street", "houseNumber", "postalCode", "city", "countryCode"),
                "properties", Map.of(
                        "street", text(200),
                        "houseNumber", text(10),
                        "houseNumberAddition", text(10),
                        "postalCode", text(20),
                        "city", text(100),
                        "countryCode", Map.of("type", "string", "pattern", "^[A-Z]{2}$")));
    }

    private static Map<String, Object> text(int maxLength) {
        return Map.of("type", "string", "maxLength", maxLength);
    }
}
