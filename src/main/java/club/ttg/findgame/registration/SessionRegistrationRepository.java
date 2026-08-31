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
     * Сколько мест занято в каждой из сессий. Место считается занятым уже по
     * поданной заявке: пока мастер её разбирает, игрок на это место
     * претендует, и показывать место свободным значило бы звать в него
     * второго. Отклонённые заявки места не держат.
     *
     * Считается разом по странице выдачи: карточка каталога показывает
     * занятые места ближайшей сессии, а запрос на игру означал бы дюжину
     * запросов на страницу.
     */
    @Query("""
            select registration.sessionId as sessionId, count(registration) as playerCount
            from SessionRegistration registration
            where registration.sessionId in :sessionIds
              and registration.status <> :excludedStatus
            group by registration.sessionId
            """)
    List<SessionPlayerCount> countTakenSeatsBySession(
            @Param("sessionIds") Collection<UUID> sessionIds,
            @Param("excludedStatus") SessionRegistrationStatus excludedStatus
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
