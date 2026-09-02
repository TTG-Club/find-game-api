package club.ttg.findgame.nexus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NexusTrackerRepository extends JpaRepository<NexusTracker, UUID> {

    List<NexusTracker> findAllByNexusIdOrderByCreatedAtDesc(UUID nexusId);

    Optional<NexusTracker> findByIdAndNexusId(UUID id, UUID nexusId);

    boolean existsByNexusIdAndTrackerId(UUID nexusId, UUID trackerId);
}
