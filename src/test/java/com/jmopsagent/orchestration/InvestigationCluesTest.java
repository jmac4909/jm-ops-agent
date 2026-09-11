package com.jmopsagent.orchestration;

import com.jmopsagent.registry.YamlServiceRegistry;
import org.springframework.core.io.ByteArrayResource;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class InvestigationCluesTest {
    private final Instant now = Instant.parse("2026-09-11T12:00:00Z");
    private final InvestigationClues clues;

    InvestigationCluesTest() {
        var registry = new YamlServiceRegistry(new ByteArrayResource("""
                services:
                  - service: entry-api
                    aliases: [entry]
                  - service: data-api
                """.getBytes(StandardCharsets.UTF_8)));
        registry.load();
        clues = new InvestigationClues(registry, new InvestigationInputProperties());
    }

    @Test
    void resolvesServiceEnvironmentTrackingAndRelativeTimeTogether() {
        var result = clues.resolve("entry returned errors in TEST yesterday, X-TrackingId: DEMO-TRACE-123", null, null, null, now);
        assertThat(result.service()).isEqualTo("entry-api");
        assertThat(result.trackingId()).isEqualTo("DEMO-TRACE-123");
        assertThat(result.environment()).isEqualTo("TEST");
        assertThat(result.window().start()).isEqualTo(Instant.parse("2026-09-10T00:00:00Z"));
        assertThat(result.window().end()).isEqualTo(Instant.parse("2026-09-11T00:00:00Z"));
        assertThat(clues.trackingId("{\"trackingId\":\"DEMO-TRACE-123\"}")).contains("DEMO-TRACE-123");
    }

    @Test
    void distinguishesFailureDatesFromMergeAndRecoveryDates() {
        var result = clues.resolve("entry-api in TEST: merged Sep 1; errors Sep 9; fixed Sep 10", null, null, null, now);
        assertThat(result.window().start()).isEqualTo(Instant.parse("2026-09-09T00:00:00Z"));
        assertThat(result.window().end()).isEqualTo(Instant.parse("2026-09-10T00:00:00Z"));
        assertThat(clues.window("entry-api deployed 2025-09-01T09:00:00Z, now failing", now)).isEmpty();
        assertThat(clues.window("entry-api failed 2025-09-09T05:00:00-04:00", now).orElseThrow().start())
                .isEqualTo(Instant.parse("2025-09-09T08:50:00Z"));
    }

    @Test
    void acceptsConcreteTrackingCluesAndRejectsAmbiguousTargetsBeforeCollection() {
        assertThat(clues.resolve("DEMO-TRACE-123", null, null, "TEST", now).trackingId()).isEqualTo("DEMO-TRACE-123");
        assertThat(clues.resolve("trace this", null, "arbitrary-supported-id", "TEST", now).trackingId()).isEqualTo("arbitrary-supported-id");
        for (String ambiguous : new String[]{"DEMO-1595", "abc123def456", "1234567890", "2025-09-09", "compare entry-api and data-api"}) {
            assertThatThrownBy(() -> clues.resolve(ambiguous, null, null, "TEST", now)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> clues.resolve("entry-api fails in DEV", null, null, "TEST", now))
                .hasMessageContaining("conflicts");
        assertThat(clues.trackingId("routerRequestId=router-123")).isEmpty();
        assertThat(clues.trackingId("trackingId=DEMO-TRACE-123 unrelated uuid=12345678-1234-1234-1234-123456789012"))
                .contains("DEMO-TRACE-123");
        assertThat(clues.trackingId("tracking id DEMO-TRACE-1 and tracking id DEMO-TRACE-2")).isEmpty();
    }

    @Test
    void supportsExplicitRangesAndOlderDatesWithoutASeparateMode() {
        assertThat(clues.window("errors between 2025-09-09T09:00:00Z and 2025-09-09T12:00:00Z", now).orElseThrow().end())
                .isEqualTo(Instant.parse("2025-09-09T12:00:00Z"));
        assertThat(clues.window("errors on September 9, 2025", now).orElseThrow().start())
                .isEqualTo(Instant.parse("2025-09-09T00:00:00Z"));
        assertThat(clues.window("failed last week", now).orElseThrow().start())
                .isEqualTo(Instant.parse("2026-08-31T00:00:00Z"));
        assertThat(clues.window("failed 14 days ago", now).orElseThrow().start())
                .isEqualTo(Instant.parse("2026-08-28T00:00:00Z"));
    }
    @Test
    void interpretsLookupPhrasesWithoutInventingAnId() {
        for (String text : new String[]{"get me last tracking ID used for QA on this service",
                "get the latest tracking id for this service", "show the most recent request ID"}) {
            assertThat(clues.latestTrackingRequested(text)).isTrue();
            assertThat(clues.trackingId(text)).isEmpty();
            var resolved = clues.resolve(text, "entry", null, "TEST", now);
            assertThat(resolved.service()).isEqualTo("entry-api");
            assertThat(resolved.trackingId()).isNull();
        }
        assertThat(clues.resolve("get me last tracking ID used for QA on entry-api", null, null, null, now).environment()).isEqualTo("TEST");
        assertThatThrownBy(() -> clues.resolve("this service not working", null, null, "TEST", now)).hasMessageContaining("affected service");
        assertThat(clues.resolve("this service not working", "entry-api", null, "TEST", now).service()).isEqualTo("entry-api");
        assertThat(clues.latestTrackingRequested("why did tracking ID DEMO-X fail last month?")).isFalse();
        assertThat(clues.trackingId("why did tracking ID DEMO-X fail last month?")).contains("DEMO-X");
        assertThat(clues.latestTrackingRequested("get the most recent application tracking ID in May 2025")).isTrue();
    }

    @Test
    void acceptsNumericDatesAndRejectsInvalidDatesWithoutSwitchingToCurrentScope() {
        assertThat(clues.window("why was this broken on 9/5", now).orElseThrow().start()).isEqualTo(Instant.parse("2026-09-05T00:00:00Z"));
        assertThat(clues.window("why was this broken on 9/5/25", now).orElseThrow().start()).isEqualTo(Instant.parse("2025-09-05T00:00:00Z"));
        assertThat(clues.window("broken 12/31", Instant.parse("2026-01-02T12:00:00Z")).orElseThrow().start()).isEqualTo(Instant.parse("2025-12-31T00:00:00Z"));
        assertThat(clues.window("broken 2/29/2024", now).orElseThrow().start()).isEqualTo(Instant.parse("2024-02-29T00:00:00Z"));
        assertThat(clues.window("deployed 9/5, now failing", now)).isEmpty();
        for (String invalid : new String[]{"broken 2/30", "broken 15/9", "broken 9/5/2099", "broken 2/29/2025"})
            assertThatThrownBy(() -> clues.window(invalid, now)).isInstanceOf(IllegalArgumentException.class);
        for (String other : new String[]{"GET /9/5", "version 9/5", "ratio 9/5", "https://example.invalid/9/5", "build v9/5"}) {
            assertThat(clues.window(other, now)).as(other).isEmpty();
        }
        var settings = new InvestigationInputProperties();
        settings.setDateOrder(InvestigationInputProperties.DateOrder.DAY_MONTH);
        var dayFirst = new InvestigationClues(org.mockito.Mockito.mock(com.jmopsagent.registry.ServiceRegistry.class), settings);
        assertThat(dayFirst.window("broken 9/5", now).orElseThrow().start()).isEqualTo(Instant.parse("2026-05-09T00:00:00Z"));
    }

    @Test
    void resolvesCalendarMonthsYearsAndMixedDatesWithoutAnAgeClamp() {
        for (String phrase : new String[]{"broken in May 2025", "broken during May 2025", "broken May 2025"}) {
            var window = clues.window(phrase, now).orElseThrow();
            assertThat(window.start()).as(phrase).isEqualTo(Instant.parse("2025-05-01T00:00:00Z"));
            assertThat(window.end()).isEqualTo(Instant.parse("2025-06-01T00:00:00Z"));
        }
        assertThat(clues.window("broken last month", now).orElseThrow().start()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(clues.window("broken three months ago, fixed yesterday", now).orElseThrow().start()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(clues.window("broken 18 months ago", now).orElseThrow().start()).isEqualTo(Instant.parse("2025-03-01T00:00:00Z"));
        assertThat(clues.window("broken in May, deployed June 1, fixed last month", now).orElseThrow().start()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(clues.window("broken last year", now).orElseThrow().end()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        var mixed = clues.window("failed from Sep 5th, 2025 to 2025-09-09", now).orElseThrow();
        assertThat(mixed.start()).isEqualTo(Instant.parse("2025-09-05T00:00:00Z"));
        assertThat(mixed.end()).isEqualTo(Instant.parse("2025-09-10T00:00:00Z"));
        assertThat(clues.window("broken in May 5, 2025", now).orElseThrow().start()).isEqualTo(Instant.parse("2025-05-05T00:00:00Z"));
        assertThat(clues.window("may I see the logs?", now)).isEmpty();
    }

    @Test
    void invalidFutureOrReversedTimestampsAndPeriodsRequireClarification() {
        for (String phrase : new String[]{"failed 2026-02-30T09:00Z", "failed 2099-09-05T10:00Z",
                "failed from 2025-09-06T09:00Z to 2025-09-05T09:00Z", "failed in May 2099",
                "failed from Sep 9, 2025 to 2025-09-05"}) {
            assertThatThrownBy(() -> clues.window(phrase, now)).as(phrase).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(clues.currentRequested("why is this failing now?")).isTrue();
        assertThat(clues.currentRequested("show current requests")).isTrue();
        assertThat(clues.currentRequested("it failed last month and is fixed now")).isFalse();
    }

}
