package club.ttg.findgame.registration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRegistrationRepository extends JpaRepository<SessionRegistration, UUID> {

    boolean existsBySessionIdAndPlayerId(UUID sessionId, UUID playerId);

    Optional<SessionRegistration> findBySessionIdAndPlayerId(UUID sessionId, UUID playerId);

    Optional<SessionRegistration> findByIdAndSessionId(UUID id, UUID sessionId);

    List<SessionRegistration> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<SessionRegistration> findAllBySessionIdInAndStatus(
            Collection<UUID> sessionIds,
            SessionRegistrationStatus status
    );

    long countBySessionIdAndStatus(UUID sessionId, SessionRegistrationStatus status);

    boolean existsBySessionIdAndPlayerIdAndStatus(
            UUID sessionId,
            UUID playerId,
            SessionRegistrationStatus status
    );

    /**
     * Сколько разных игроков принято в игры. Считается разом по странице
     * выдачи: карточка каталога показывает занятые места, а запрос на игру
     * означал бы восемь запросов на страницу.
     */
    @Query("""
            select session.gameId as gameId, count(distinct registration.playerId) as playerCount
            from SessionRegistration registration
            join GameSession session on session.id = registration.sessionId
            where session.gameId in :gameIds
              and registration.status = :status
            group by session.gameId
            """)
    List<GamePlayerCount> countApprovedPlayersByGame(
            @Param("gameIds") Collection<UUID> gameIds,
            @Param("status") SessionRegistrationStatus status
    );

    @Query("""
            select (count(registration) > 0)
            from SessionRegistration registration
            join GameSession session on session.id = registration.sessionId
            where session.gameId = :gameId
              and registration.playerId = :playerId
              and registration.status = :status
            """)
    boolean existsApprovedPlayerInGame(
            @Param("gameId") UUID gameId,
            @Param("playerId") UUID playerId,
            @Param("status") SessionRegistrationStatus status
    );
}
