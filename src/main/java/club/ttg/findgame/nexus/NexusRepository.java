package club.ttg.findgame.nexus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NexusRepository extends JpaRepository<Nexus, UUID> {

    Optional<Nexus> findByInviteCode(UUID inviteCode);

    Optional<Nexus> findByGameId(UUID gameId);

    /**
     * Комнаты пользователя: свои и те, куда он вошёл по ссылке.
     *
     * Комнаты игр сюда не попадают: в них ходят со страницы игры, и в общем
     * списке они смешались бы с теми, куда позвали лично.
     */
    @Query("""
            SELECT n FROM Nexus n
            WHERE n.gameId IS NULL
              AND (n.ownerId = :userId
                   OR EXISTS (SELECT 1 FROM NexusMember m
                              WHERE m.nexusId = n.id AND m.userId = :userId))
            """)
    Page<Nexus> findAllAvailable(@Param("userId") UUID userId, Pageable pageable);
}
