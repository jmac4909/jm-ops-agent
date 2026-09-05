package com.jmopsagent;

import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.RegistryProvenance;
import com.jmopsagent.registry.RegistryValue;
import com.jmopsagent.registry.YamlServiceRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckedInPublicationFixtureTest {
    private static final Set<String> FICTIONAL_SERVICES = Set.of(
            "edge-gateway", "identity-service", "catalog-service");
    private static final Pattern ALLOWED_ATTRIBUTE_PATH = Pattern.compile(
            "(?:runtime\\.platform\\.(?:DEV|TEST)|gitlab\\.repository|(?:tas|eks)Branches|"
                    + "eks\\.(?:namespace(?:\\.(?:DEV|TEST))?|deployment|service)|"
                    + "tas\\.(?:appPattern|target(?:\\.(?:DEV|TEST))?)|"
                    + "jenkins\\.(?:job|controller)(?:\\.(?:DEV|TEST))?|"
                    + "splunk\\.(?:indexes\\.[A-Za-z0-9_-]+|"
                    + "(?:appNames|gatewayNames|fieldProfiles)(?:\\.(?:DEV|TEST))?)|"
                    + "health\\.(?:readinessPath|livenessPath))");

    @Test
    void checkedInRegistryRemainsSmallAndObviouslyFictional() {
        YamlServiceRegistry registry = new YamlServiceRegistry();
        registry.load();
        List<ServiceDefinition> definitions = List.copyOf(registry.all());

        assertThat(definitions).hasSize(FICTIONAL_SERVICES.size());
        assertThat(definitions.stream().map(ServiceDefinition::service).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(FICTIONAL_SERVICES);
        assertThat(definitions).allSatisfy(definition -> {
            assertThat(definition.aliases()).isNotEmpty()
                    .allMatch(alias -> alias.startsWith("demo-"));
            assertThat(definition.aliasesWithProvenance().values())
                    .allSatisfy(CheckedInPublicationFixtureTest::assertManualConfirmed);

            String repository = definition.attributeValue("gitlab.repository").orElseThrow();
            URI repositoryUri = URI.create(repository);
            assertThat(repositoryUri.getScheme()).isEqualTo("https");
            assertThat(repositoryUri.getHost()).endsWith(".invalid");
            assertThat(repositoryUri.getUserInfo()).isNull();
            assertThat(repositoryUri.getQuery()).isNull();
            assertThat(repositoryUri.getFragment()).isNull();
            assertThat(repositoryUri.getPath()).startsWith("/demo/");

            assertThat(definition.attributes()).isNotEmpty();
            assertThat(definition.attributes().keySet())
                    .allMatch(path -> ALLOWED_ATTRIBUTE_PATH.matcher(path).matches());
            assertThat(definition.attributes().values())
                    .allSatisfy(CheckedInPublicationFixtureTest::assertManualConfirmed);

            List<Map.Entry<String, RegistryValue>> indexes = definition.attributes().entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("splunk.indexes."))
                    .toList();
            assertThat(indexes).isNotEmpty().allSatisfy(entry ->
                    assertThat(entry.getValue().values()).allMatch(value -> value.startsWith("demo_")));

            List<Map.Entry<String, RegistryValue>> jobs = matchingAttributes(definition, "jenkins.job");
            assertThat(jobs).isNotEmpty().allSatisfy(entry ->
                    assertThat(entry.getValue().values()).allMatch(value -> value.startsWith("demo/")));

            definition.attributes().entrySet().stream()
                    .filter(entry -> isFictionalRoutingIdentifier(entry.getKey()))
                    .forEach(entry -> assertThat(entry.getValue().values())
                            .allMatch(value -> value.startsWith("demo-")));
        });
    }

    private static List<Map.Entry<String, RegistryValue>> matchingAttributes(
            ServiceDefinition definition, String path) {
        return definition.attributes().entrySet().stream()
                .filter(entry -> entry.getKey().equals(path) || entry.getKey().startsWith(path + "."))
                .toList();
    }

    private static boolean isFictionalRoutingIdentifier(String path) {
        return path.equals("tasBranches") || path.equals("eksBranches")
                || path.equals("eks.deployment") || path.equals("eks.service")
                || path.equals("tas.appPattern") || path.startsWith("eks.namespace.")
                || path.equals("eks.namespace") || path.equals("tas.target")
                || path.startsWith("tas.target.") || path.equals("jenkins.controller")
                || path.startsWith("jenkins.controller.") || path.equals("splunk.appNames")
                || path.startsWith("splunk.appNames.") || path.equals("splunk.gatewayNames")
                || path.startsWith("splunk.gatewayNames.") || path.equals("splunk.fieldProfiles")
                || path.startsWith("splunk.fieldProfiles.");
    }

    private static void assertManualConfirmed(RegistryValue value) {
        assertThat(value.provenance()).isEqualTo(RegistryProvenance.MANUAL);
        assertThat(value.confirmed()).isTrue();
    }

    @Test
    void checkedInEnvironmentTemplateLeavesExternalIdentitiesAndCredentialsBlank() throws Exception {
        List<EnvAssignment> assignments = Files.readAllLines(Path.of(".env.example"), StandardCharsets.UTF_8)
                .stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.contains("="))
                .map(line -> line.split("=", 2))
                .map(parts -> new EnvAssignment(parts[0], parts[1]))
                .toList();

        Predicate<String> mustRemainBlank = key -> key.contains("TOKEN")
                || key.contains("PASSWORD")
                || key.contains("PASSPHRASE")
                || key.contains("SECRET")
                || key.contains("COOKIE")
                || key.contains("FORM_KEY")
                || key.contains("USERNAME")
                || key.contains("API_KEY")
                || key.contains("ACCESS_KEY")
                || key.contains("PRIVATE_KEY")
                || key.endsWith("_BASE_URL")
                || key.endsWith("_CONTEXT")
                || key.endsWith("_NAMESPACE")
                || key.endsWith("_INDEXES")
                || key.matches("CF_.*_(?:API|ORG|SPACE|HOME)");

        assertThat(assignments).extracting(EnvAssignment::key).doesNotHaveDuplicates();
        assertThat(assignments.stream().filter(entry -> mustRemainBlank.test(entry.key())))
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.value())
                        .as("external value for %s", entry.key())
                        .isEmpty());
    }

    private record EnvAssignment(String key, String value) {}
}
