package club.ttg.findgame.game;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<Game, UUID>, JpaSpecificationExecutor<Game> {

    boolean existsByMasterIdAndStatusNotAndDeletedAtIsNull(UUID masterId, GameStatus status);

    Optional<Game> findByIdAndVisibilityAndDeletedAtIsNull(UUID id, GameVisibility visibility);

    Optional<Game> findByIdAndInviteCodeAndDeletedAtIsNull(UUID id, UUID inviteCode);

    Optional<Game> findByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from Game game where game.id = :id and game.deletedAt is null")
    Optional<Game> findByIdForUpdate(@Param("id") UUID id);
}
