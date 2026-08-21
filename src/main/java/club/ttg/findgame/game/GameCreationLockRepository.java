package club.ttg.findgame.game;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface GameCreationLockRepository extends JpaRepository<GameCreationLock, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO game_creation_locks (master_id)
            VALUES (:masterId)
            ON CONFLICT (master_id) DO NOTHING
            """, nativeQuery = true)
    void ensureExists(@Param("masterId") UUID masterId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select creationLock from GameCreationLock creationLock where creationLock.masterId = :masterId")
    Optional<GameCreationLock> findByMasterIdForUpdate(@Param("masterId") UUID masterId);
}
