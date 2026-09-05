package com.jmopsagent.orchestration;

import com.jmopsagent.domain.Investigation;
import com.jmopsagent.persistence.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
class PersistedSplunkBudgetIntegrationTest {
    @Autowired InvestigationApplicationService investigations;
    @Autowired InvestigationStateService state;
    @Autowired InvestigationRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void reservesSearchesAgainstOnePersistedInvestigationWideLimit() {
        Investigation investigation = investigations.createTrackingInvestigation("DEMO-BUDGET-001", "TEST");

        assertThat(state.tryReserveSplunkSearch(investigation.getId(), 2)).isTrue();
        assertThat(state.tryReserveSplunkSearch(investigation.getId(), 2)).isTrue();
        assertThat(state.tryReserveSplunkSearch(investigation.getId(), 2)).isFalse();
        assertThat(investigations.get(investigation.getId()).getSplunkSearchCount()).isEqualTo(2);
    }
}
