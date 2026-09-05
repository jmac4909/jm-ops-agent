package com.jmopsagent.persistence;

import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvestigationRepository extends JpaRepository<Investigation, UUID> {

    List<Investigation> findAllByOrderByStartedAtDesc(Pageable pageable);

    List<Investigation> findByServiceIgnoreCaseAndEnvironmentOrderByStartedAtDesc(
            String service, DeploymentEnvironment environment, Pageable pageable);

    List<Investigation> findByStatusOrderByCompletedAtDesc(InvestigationStatus status, Pageable pageable);

    @Query("select distinct i from Investigation i left join fetch i.evidenceItems where i.id = :id")
    Optional<Investigation> findWithEvidenceById(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Investigation i set i.splunkSearchCount = i.splunkSearchCount + 1, "
            + "i.version = i.version + 1 where i.id = :id and i.splunkSearchCount < :maximum")
    int reserveSplunkSearch(@Param("id") UUID id, @Param("maximum") int maximum);
}
