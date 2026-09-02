package club.ttg.findgame.nexus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NexusCharacterSheetRepository
        extends JpaRepository<NexusCharacterSheet, UUID> {

    List<NexusCharacterSheet> findAllByNexusIdOrderByCreatedAtAsc(UUID nexusId);

    Optional<NexusCharacterSheet> findByIdAndNexusId(UUID id, UUID nexusId);

    boolean existsByNexusIdAndShareToken(UUID nexusId, String shareToken);
}
