package com.tikkie.person.infra.constructs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.logs.LogGroup;

class PersonalDataLogProtectionTest {

    private static final String LOG_GROUP = "AWS::Logs::LogGroup";
    private static final List<String> MANAGED_IDENTIFIERS = List.of(
            "Address", "PhoneNumber-DE", "PhoneNumber-ES", "PhoneNumber-FR", "PhoneNumber-GB", "PhoneNumber-IT");
    private static final Pattern E164 = Pattern.compile(PersonalDataLogProtection.E164_PATTERN);

    @Test
    void auditsAndMasksTheSameIdentifiers() {
        List<Map<String, Object>> statements = statements(policyOnAPlainLogGroup());

        assertThat(statements).hasSize(2);
        assertThat(statements).extracting(statement -> operation(statement).keySet())
                .containsExactly(Set.of("Audit"), Set.of("Deidentify"));
        assertThat(identifierNames(statements.get(0))).isEqualTo(identifierNames(statements.get(1)));
    }

    @Test
    void coversAddressesPostalCodesAndPhoneNumbers() {
        List<String> names = identifierNames(statements(policyOnAPlainLogGroup()).get(1));

        assertThat(names).containsExactlyInAnyOrderElementsOf(
                Stream.concat(MANAGED_IDENTIFIERS.stream(), Stream.of("E164PhoneNumber", "DutchPostalCode"))
                        .toList());
    }

    @Test
    void masksMatchesInPlace() {
        Map<String, Object> deidentify = operation(statements(policyOnAPlainLogGroup()).get(1));

        assertThat(deidentify).isEqualTo(Map.of("Deidentify", Map.of("MaskConfig", Map.of())));
    }

    @Test
    void sendsAuditFindingsNowhere() {
        Map<String, Object> audit = operation(statements(policyOnAPlainLogGroup()).get(0));

        assertThat(audit).isEqualTo(Map.of("Audit", Map.of("FindingsDestination", Map.of())));
    }

    @Test
    void definesTheCustomIdentifiers() {
        assertThat(policyOnAPlainLogGroup().get("Configuration")).isEqualTo(Map.of("CustomDataIdentifier", List.of(
                Map.of("Name", "E164PhoneNumber", "Regex", PersonalDataLogProtection.E164_PATTERN),
                Map.of("Name", "DutchPostalCode", "Regex", "[1-9][0-9]{3} [A-Z]{2}"))));
    }

    // CloudWatch accepts only some regex characters in a custom identifier: no groups, at most 200 characters.
    @Test
    void customIdentifierRegexesStayWithinCloudWatchLimits() {
        assertThat(customIdentifierRegexes().values()).allSatisfy(regex -> assertThat(regex)
                .hasSizeLessThanOrEqualTo(200)
                .doesNotContain("(", ")"));
    }

    // 1024 MB is a file size, not an address. Masking it is an accepted false positive.
    @ParameterizedTest
    @ValueSource(strings = {"1012 LG", "\"postalCode\":\"1012 LG\"", "1024 MB"})
    void dutchPostalCodePatternFindsPostalCodesAsTheServiceStoresThem(String text) {
        assertThat(dutchPostalCode().matcher(text).find()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0123 AB", "1012LG", "1012 lg"})
    void dutchPostalCodePatternIgnoresTextThatIsNotAStoredPostalCode(String text) {
        assertThat(dutchPostalCode().matcher(text).find()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+31612345678", "+4930123456", "\"phone\":\"+31612345678\""})
    void e164PatternFindsPhoneNumbersAsTheServiceStoresThem(String text) {
        assertThat(E164.matcher(text).find()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+0612345678", "+12345", "31612345678", "2026-10-21T10:15:30Z"})
    void e164PatternIgnoresTextThatIsNotAnE164Number(String text) {
        assertThat(E164.matcher(text).find()).isFalse();
    }

    private static Pattern dutchPostalCode() {
        return Pattern.compile(customIdentifierRegexes().get("DutchPostalCode"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> customIdentifierRegexes() {
        Map<String, String> regexes = new TreeMap<>();
        Map<String, Object> configuration = (Map<String, Object>) policyOnAPlainLogGroup().get("Configuration");
        for (Map<String, String> identifier : (List<Map<String, String>>) configuration.get("CustomDataIdentifier")) {
            regexes.put(identifier.get("Name"), identifier.get("Regex"));
        }
        return regexes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> policyOnAPlainLogGroup() {
        Stack stack = PersonFunctionTemplates.stack();
        LogGroup.Builder.create(stack, "Logs").dataProtectionPolicy(PersonalDataLogProtection.policy()).build();
        return (Map<String, Object>) properties(Template.fromStack(stack).findResources(LOG_GROUP).values().iterator().next())
                .get("DataProtectionPolicy");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> statements(Map<String, Object> policy) {
        return (List<Map<String, Object>>) policy.get("Statement");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> operation(Map<String, Object> statement) {
        return (Map<String, Object>) statement.get("Operation");
    }

    private static List<String> identifierNames(Map<String, Object> statement) {
        return ((List<?>) statement.get("DataIdentifier")).stream()
                .map(PersonalDataLogProtectionTest::identifierName)
                .toList();
    }

    // Managed identifiers are ARNs ending in /<name>; custom identifiers are plain names.
    private static String identifierName(Object identifier) {
        String text = identifier.toString();
        return text.substring(text.lastIndexOf('/') + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> resource) {
        return (Map<String, Object>) resource.get("Properties");
    }
}
