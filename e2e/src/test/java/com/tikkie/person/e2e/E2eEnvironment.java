package com.tikkie.person.e2e;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.regions.Region;

record E2eEnvironment(URI personUri, String queueUrl, Region region) {

    static final String API_URL = "E2E_API_URL";
    static final String QUEUE_URL = "E2E_QUEUE_URL";
    static final String REGION = "AWS_REGION";

    // Without the stack outputs the tests are skipped, so the default build and CI never need AWS.
    static E2eEnvironment requireDeployedService(Map<String, String> variables) {
        String apiUrl = variables.get(API_URL);
        String queueUrl = variables.get(QUEUE_URL);
        assumeTrue(isSet(apiUrl) && isSet(queueUrl),
                "Set " + API_URL + " and " + QUEUE_URL + " to run against a deployed stack");
        String region = variables.get(REGION);
        if (!isSet(region)) {
            throw new IllegalStateException("Set " + REGION + " to the region of the deployed stack");
        }
        return new E2eEnvironment(personUri(apiUrl), queueUrl, Region.of(region));
    }

    private static URI personUri(String apiUrl) {
        String stageUrl = apiUrl.endsWith("/") ? apiUrl : apiUrl + "/";
        return URI.create(stageUrl + "person");
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
