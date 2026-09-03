package club.ttg.findgame.registration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameRegistrationRepository extends JpaRepository<GameRegistration, UUID> {

    Optional<GameRegistration> findByGameIdAndPlayerId(UUID gameId, UUID playerId);

    Optional<GameRegistration> findByIdAndGameId(UUID id, UUID gameId);

    List<GameRegistration> findAllByGameIdOrderByCreatedAtAsc(UUID gameId);

    List<GameRegistration> findAllByGameIdAndStatus(UUID gameId, RegistrationStatus status);

    long countByGameIdAndStatus(UUID gameId, RegistrationStatus status);

    /**
     * Сколько мест занято в игре. Место занимает любая заявка, кроме
     * отклонённой: пока мастер её разбирает, игрок на это место претендует.
     */
    long countByGameIdAndStatusNot(UUID gameId, RegistrationStatus status);

    boolean existsByGameIdAndPlayerIdAndStatus(
            UUID gameId, UUID playerId, RegistrationStatus status);

    /**
     * Есть ли у игрока в игре заявка, которую не отклонили. По ней
     * открывается общий чат игры и личная переписка с мастером: до решения
     * мастера игроку есть о чём с ним говорить.
     */
    boolean existsByGameIdAndPlayerIdAndStatusNot(
            UUID gameId, UUID playerId, RegistrationStatus status);

    /**
     * Сколько мест занято в каждой из игр и сколько из них подтверждено.
     * Место занимает любая заявка, кроме отклонённой: пока мастер её
     * разбирает, игрок на это место уже претендует.
     *
     * Считается разом по странице выдачи — запрос на игру означал бы дюжину
     * запросов на страницу каталога.
     */
    @Query("""
            select registration.gameId as gameId,
                   count(registration) as playerCount,
                   sum(case when registration.status = :approvedStatus then 1 else 0 end)
                       as approvedCount
            from GameRegistration registration
            where registration.gameId in :gameIds
              and registration.status <> :excludedStatus
            group by registration.gameId
            """)
    List<GameSeatCount> countTakenSeatsByGame(
            @Param("gameIds") Collection<UUID> gameIds,
            @Param("excludedStatus") RegistrationStatus excludedStatus,
            @Param("approvedStatus") RegistrationStatus approvedStatus
    );
}
