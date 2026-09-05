package com.jmopsagent.registry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RegistryValue(
        List<String> values,
        RegistryProvenance provenance,
        Instant updatedAt,
        boolean confirmed
) {
    public RegistryValue {
        values = values == null ? List.of() : values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        Objects.requireNonNull(provenance, "provenance");
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static RegistryValue manual(List<String> values) {
        return new RegistryValue(values, RegistryProvenance.MANUAL, Instant.now(), true);
    }

    public static RegistryValue discovered(List<String> values, RegistryProvenance provenance) {
        if (provenance == RegistryProvenance.MANUAL) {
            throw new IllegalArgumentException("A discovery update cannot claim MANUAL provenance");
        }
        return new RegistryValue(values, provenance, Instant.now(), false);
    }

    public Optional<String> value() {
        return values.stream().findFirst();
    }
}
