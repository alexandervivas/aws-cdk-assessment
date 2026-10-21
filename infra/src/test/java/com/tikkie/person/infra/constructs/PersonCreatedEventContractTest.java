package com.tikkie.person.infra.constructs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// The sample NewImage is hand-written from PersonItemMapper's item shape: infra does not depend on the service
// module, and the mapper is package-private there.
class PersonCreatedEventContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SCHEMA = Path.of("../docs/events/person-created.v1.json");
    private static final Path INSERT_RECORD = Path.of("src/test/resources/events/persons-stream-insert.json");
    private static final Set<String> ATTRIBUTES_IN_EVENT = Set.of("personId", "createdAt", "correlationId");

    @Test
    void renderedEventSatisfiesTheSchema() {
        assertThat(schemaErrors(envelope(renderedDetail()))).isEmpty();
    }

    @Test
    void renderedEventCarriesTheStreamRecordIdentifiers() {
        assertThat(renderedDetail()).isEqualTo(json("""
                {"metadata":{"eventId":"c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5","eventVersion":"1",
                 "occurredAt":"2026-10-21T09:15:30.123Z","correlationId":"corr-8d3f9a"},
                 "data":{"personId":"9b2e6c1a-5f3d-4a8e-b7c2-0d1e2f3a6b5c"}}
                """));
    }

    @Test
    void renderedEventCarriesNoOtherValueOfTheNewImage() {
        String rendered = PipeTemplateRenderer.render(PersonEvents.INPUT_TEMPLATE, insertRecord());
        List<String> personalValues = stringValuesOutsideTheEvent(insertRecord().at("/dynamodb/NewImage"));

        assertThat(personalValues).containsExactlyInAnyOrder(
                "Anna", "de Vries", "+31612345678", "Damrak", "47", "III", "1012 LG", "Amsterdam", "NL");
        assertThat(personalValues).allSatisfy(value -> assertThat(rendered).doesNotContain(value));
    }

    static Stream<Named<Consumer<ObjectNode>>> invalidEvents() {
        return Stream.of(
                Named.of("another source", event -> event.put("source", "tikkie.other-service")),
                Named.of("another detail type", event -> event.put("detail-type", "PersonUpdated")),
                Named.of("an extra field in data", event -> data(event).put("firstName", "Anna")),
                Named.of("an extra field in metadata", event -> metadata(event).put("callerId", "client")),
                Named.of("an extra field in detail", event -> detail(event).put("person", "x")),
                Named.of("another event version", event -> metadata(event).put("eventVersion", "2")),
                Named.of("a personId that is not a UUID", event -> data(event).put("personId", "person-1")),
                Named.of("an occurredAt that is not a date-time", event -> metadata(event).put("occurredAt", "today")),
                Named.of("an unsafe correlationId", event -> metadata(event).put("correlationId", "a b")),
                Named.of("an empty eventId", event -> metadata(event).put("eventId", "")),
                Named.of("no correlationId", event -> metadata(event).remove("correlationId")),
                Named.of("no data", event -> detail(event).remove("data")));
    }

    @ParameterizedTest
    @MethodSource("invalidEvents")
    void schemaRejects(Consumer<ObjectNode> change) {
        ObjectNode event = envelope(renderedDetail());
        change.accept(event);

        assertThat(schemaErrors(event)).isNotEmpty();
    }

    @Test
    void filterPassesInsertRecords() {
        assertThat(EventPatternMatcher.matches(json(PersonEvents.INSERT_ONLY_FILTER), insertRecord())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODIFY", "REMOVE"})
    void filterDropsOtherRecords(String eventName) {
        ObjectNode record = insertRecord();
        record.put("eventName", eventName);

        assertThat(EventPatternMatcher.matches(json(PersonEvents.INSERT_ONLY_FILTER), record)).isFalse();
    }

    private static ObjectNode envelope(JsonNode detail) {
        ObjectNode event = JSON.createObjectNode();
        event.put("source", PersonEvents.EVENT_SOURCE);
        event.put("detail-type", PersonEvents.DETAIL_TYPE);
        event.set("detail", detail);
        return event;
    }

    private static ObjectNode detail(ObjectNode event) {
        return (ObjectNode) event.get("detail");
    }

    private static ObjectNode metadata(ObjectNode event) {
        return (ObjectNode) detail(event).get("metadata");
    }

    private static ObjectNode data(ObjectNode event) {
        return (ObjectNode) detail(event).get("data");
    }

    private static JsonNode renderedDetail() {
        return json(PipeTemplateRenderer.render(PersonEvents.INPUT_TEMPLATE, insertRecord()));
    }

    private static List<String> schemaErrors(JsonNode event) {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(read(SCHEMA));
        // Draft 2020-12 treats "format" as an annotation unless assertions are switched on.
        return schema.validate(event, context -> context.executionConfig(c -> c.formatAssertionsEnabled(true)))
                .stream()
                .map(Error::getMessage)
                .toList();
    }

    private static List<String> stringValuesOutsideTheEvent(JsonNode newImage) {
        List<String> values = new ArrayList<>();
        newImage.properties().stream()
                .filter(attribute -> !ATTRIBUTES_IN_EVENT.contains(attribute.getKey()))
                .forEach(attribute -> collectStrings(attribute.getValue(), values));
        return values;
    }

    private static void collectStrings(JsonNode attributeValue, List<String> values) {
        if (attributeValue.has("S")) {
            values.add(attributeValue.get("S").asText());
        }
        attributeValue.path("M").forEach(nested -> collectStrings(nested, values));
    }

    private static ObjectNode insertRecord() {
        return (ObjectNode) json(read(INSERT_RECORD));
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
