package com.tikkie.person.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;

// A drift alarm, not a spec: the assertion tests define the infrastructure, and this test flags any template change
// they don't cover. Review the diff of a refreshed snapshot like code.
class PersonServiceSnapshotTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/snapshots");
    private static final boolean UPDATE = Boolean.getBoolean("snapshot.update");
    private static final String REFRESH = "Refresh with ./mvnw -pl infra test -Dsnapshot.update=true and review the diff";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    // The shaded jar is not byte-for-byte reproducible, so its asset hash and the version id derived from it vary
    // per build.
    private static final Pattern ASSET_KEY = Pattern.compile("(\"S3Key\" : \")[0-9a-f]{64}(\\.jar\")");
    private static final Pattern ASSET_PATH = Pattern.compile("(asset\\.)[0-9a-f]{64}(\\.jar)");
    private static final Pattern CURRENT_VERSION_ID = Pattern.compile("CurrentVersion[0-9A-Fa-f]{32,}");

    @ParameterizedTest
    @ValueSource(strings = {"Dev", "Prod"})
    void matchesTheCommittedTemplate(String stageId) throws IOException {
        Path snapshot = SNAPSHOTS.resolve(stageId + ".template.json");
        String actual = normalise(template(stageId));

        if (UPDATE) {
            Files.createDirectories(SNAPSHOTS);
            Files.writeString(snapshot, actual);
            fail("Snapshot " + snapshot + " updated. Rerun without -Dsnapshot.update to check it.");
        }

        assertThat(snapshot).as("Missing snapshot. " + REFRESH).exists();
        assertThat(actual).as(stageId + " template drifted from its snapshot. " + REFRESH)
                .isEqualToNormalizingNewlines(Files.readString(snapshot));
    }

    private static Map<String, Object> template(String stageId) {
        App app = CdkTestApps.withCdkJsonContext();
        PersonServiceApp.addStages(app, Map.of());
        return Template.fromStack(((PersonServiceStage) app.getNode().findChild(stageId)).stack()).toJSON();
    }

    private static String normalise(Map<String, Object> template) throws IOException {
        String json = JSON.writeValueAsString(template) + "\n";
        json = ASSET_KEY.matcher(json).replaceAll("$1ASSET_HASH$2");
        json = ASSET_PATH.matcher(json).replaceAll("$1ASSET_HASH$2");
        return CURRENT_VERSION_ID.matcher(json).replaceAll("CurrentVersionHASH");
    }
}
