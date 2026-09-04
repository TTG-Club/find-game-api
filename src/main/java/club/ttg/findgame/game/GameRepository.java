package club.ttg.findgame.game;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<Game, UUID>, JpaSpecificationExecutor<Game> {

    boolean existsByMasterIdAndStatusNotAndDeletedAtIsNull(UUID masterId, GameStatus status);

    Page<Game> findAllByMasterIdAndDeletedAtIsNull(UUID masterId, Pageable pageable);

    /**
     * Игры, к которым пользователь причастен: свои как мастер и те, куда он
     * подал заявку или принят игроком.
     *
     * Отклонённая заявка причастности не даёт: игра, куда не взяли, в личном
     * списке только мешает.
     */
    @Query("""
            SELECT g FROM Game g
            WHERE g.deletedAt IS NULL
              AND (g.masterId = :userId
                   OR EXISTS (SELECT 1 FROM GameRegistration r
                              WHERE r.gameId = g.id
                                AND r.playerId = :userId
                                AND r.status <> club.ttg.findgame.registration.RegistrationStatus.REJECTED))
            """)
    Page<Game> findAllOwnOrJoined(@Param("userId") UUID userId, Pageable pageable);

    /**
     * То же, но с отбором по статусу: отменённые игры показываются только
     * тому, кто спросил их прямо.
     */
    @Query("""
            SELECT g FROM Game g
            WHERE g.deletedAt IS NULL
              AND g.status IN :statuses
              AND (g.masterId = :userId
                   OR EXISTS (SELECT 1 FROM GameRegistration r
                              WHERE r.gameId = g.id
                                AND r.playerId = :userId
                                AND r.status <> club.ttg.findgame.registration.RegistrationStatus.REJECTED))
            """)
    Page<Game> findAllOwnOrJoinedByStatus(
            @Param("userId") UUID userId,
            @Param("statuses") Collection<GameStatus> statuses,
            Pageable pageable);

    Optional<Game> findByIdAndVisibilityAndDeletedAtIsNull(UUID id, GameVisibility visibility);

    Optional<Game> findByIdAndInviteCodeAndDeletedAtIsNull(UUID id, UUID inviteCode);

    Optional<Game> findByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from Game game where game.id = :id and game.deletedAt is null")
    Optional<Game> findByIdForUpdate(@Param("id") UUID id);
}
