package club.ttg.findgame.registration;

import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRegistrationRepository extends JpaRepository<SessionRegistration, UUID> {

    boolean existsBySessionIdAndPlayerId(UUID sessionId, UUID playerId);

    Optional<SessionRegistration> findBySessionIdAndPlayerId(UUID sessionId, UUID playerId);

    List<SessionRegistration> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<SessionRegistration> findAllBySessionIdIn(Collection<UUID> sessionIds);

    long countBySessionId(UUID sessionId);

    /**
     * На скольких состоявшихся встречах игрок был в составе. Счётчик для его
     * профиля: отдельно сыгранные встречи никто не ведёт, и разойтись с
     * правдой такому счёту негде.
     *
     * @param playerId Игрок.
     * @param status Статус состоявшейся встречи.
     */
    @Query("""
            select count(participation) from SessionRegistration participation
            where participation.playerId = :playerId
              and participation.sessionId in (
                  select session.id from GameSession session
                  where session.status = :status)
            """)
    long countCompletedByPlayer(
            @Param("playerId") UUID playerId,
            @Param("status") GameSessionStatus status);

    /**
     * Убирает игрока из перечисленных сессий. Мастер исключает его из игры
     * целиком, и участие снимается во всех незакрытых встречах; в закрытых
     * оно остаётся историей.
     */
    void deleteBySessionIdInAndPlayerId(Collection<UUID> sessionIds, UUID playerId);

    /**
     * Кто из игроков побывал на прошедших встречах игры: на закрытых или на
     * тех, что по дате уже прошли, хотя мастер их не закрыл. Отказавшийся
     * прийти встречу не играл.
     *
     * @param gameId Игра.
     * @param absent Отметка «не приду».
     * @param completed Статус закрытой встречи.
     * @param open Статусы незакрытых встреч.
     * @param startedBefore Незакрытая встреча до этой отметки считается прошедшей.
     */
    @Query("""
            select distinct participation.playerId
            from SessionRegistration participation, GameSession session
            where session.id = participation.sessionId
              and session.gameId = :gameId
              and (participation.attendanceStatus is null
                   or participation.attendanceStatus <> :absent)
              and (session.status = :completed
                   or (session.status in :open and session.startsAt < :startedBefore))
            """)
    List<UUID> findPlayedPlayerIds(
            @Param("gameId") UUID gameId,
            @Param("absent") SessionAttendanceStatus absent,
            @Param("completed") GameSessionStatus completed,
            @Param("open") Collection<GameSessionStatus> open,
            @Param("startedBefore") Instant startedBefore);

    /** Игры, на прошедших встречах которых игрок побывал, — по тем же правилам. */
    @Query("""
            select distinct session.gameId
            from SessionRegistration participation, GameSession session
            where session.id = participation.sessionId
              and participation.playerId = :playerId
              and (participation.attendanceStatus is null
                   or participation.attendanceStatus <> :absent)
              and (session.status = :completed
                   or (session.status in :open and session.startsAt < :startedBefore))
            """)
    List<UUID> findPlayedGameIds(
            @Param("playerId") UUID playerId,
            @Param("absent") SessionAttendanceStatus absent,
            @Param("completed") GameSessionStatus completed,
            @Param("open") Collection<GameSessionStatus> open,
            @Param("startedBefore") Instant startedBefore);
}
