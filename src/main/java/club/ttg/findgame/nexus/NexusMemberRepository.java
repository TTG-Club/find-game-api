package club.ttg.findgame.nexus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NexusMemberRepository extends JpaRepository<NexusMember, UUID> {

    boolean existsByNexusIdAndUserId(UUID nexusId, UUID userId);

    List<NexusMember> findAllByNexusIdOrderByJoinedAtAsc(UUID nexusId);

    void deleteByNexusIdAndUserId(UUID nexusId, UUID userId);
}
