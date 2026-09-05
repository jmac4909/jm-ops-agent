package com.jmopsagent.tas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TasPropertiesTest {

    @Test
    void bindsMultipleLogicalTargetsForTheSameAllowedEnvironment() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "jmops.tas.targets.group-one-test.environment", "TEST",
                "jmops.tas.targets.group-one-test.api", "https://api.test.example.invalid",
                "jmops.tas.targets.group-one-test.org", "group-one",
                "jmops.tas.targets.group-one-test.space", "test-space",
                "jmops.tas.targets.group-one-test.home", "C:/jmops/cf/group-one-test",
                "jmops.tas.targets.group-two-test.environment", "TEST",
                "jmops.tas.targets.group-two-test.api", "https://api.test.example.invalid",
                "jmops.tas.targets.group-two-test.org", "group-two",
                "jmops.tas.targets.group-two-test.space", "test-space",
                "jmops.tas.targets.group-two-test.home", "C:/jmops/cf/group-two-test"));

        TasProperties properties = new Binder(source)
                .bind("jmops.tas", Bindable.of(TasProperties.class))
                .orElseThrow(() -> new AssertionError("TAS properties did not bind"));

        assertThat(properties.getTargets()).containsOnlyKeys("group-one-test", "group-two-test");
        assertThat(properties.getTargets().get("group-one-test").getEnvironment()).isEqualTo("TEST");
        assertThat(properties.getTargets().get("group-two-test").getHome())
                .isEqualTo("C:/jmops/cf/group-two-test");
    }
}
