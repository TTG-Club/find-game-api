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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface GameRepository extends JpaRepository<Game, UUID>, JpaSpecificationExecutor<Game> {

    /**
     * Ролевые вкладки и краткая сводка личного кабинета с серверной пагинацией.
     *
     * Вкладка избранного показывает лишь то, что пользователь и так вправе
     * открыть: отметка — закладка, а не пропуск. Чужая приватная игра из
     * списка уходит вместе с доступом к ней, иначе карточка вела бы на 404.
     */
    @Query("""
            select game from Game game
            where game.deletedAt is null and game.status in :statuses
              and (
                (:role = 'MASTER' and game.masterId = :userId)
                or (:role = 'PLAYER' and exists (select 1 from GameRegistration registration
                    where registration.gameId = game.id and registration.playerId = :userId
                      and registration.status = club.ttg.findgame.registration.RegistrationStatus.APPROVED))
                or (:role = 'APPLICATIONS' and exists (select 1 from GameRegistration registration
                    where registration.gameId = game.id and registration.playerId = :userId
                      and registration.status = club.ttg.findgame.registration.RegistrationStatus.PENDING))
                or (:role = 'ATTENTION' and game.masterId = :userId and
                    (exists (select 1 from GameRegistration registration where registration.gameId = game.id
                        and registration.status = club.ttg.findgame.registration.RegistrationStatus.PENDING)
                     or exists (select 1 from GameSession session where session.gameId = game.id
                        and session.status = club.ttg.findgame.session.GameSessionStatus.SCHEDULED
                        and session.startsAt is null)))
                or (:role = 'FAVORITE'
                    and exists (select 1 from FavoriteGame favorite
                        where favorite.gameId = game.id and favorite.ownerId = :userId)
                    and (game.visibility = club.ttg.findgame.game.GameVisibility.PUBLIC
                         or game.masterId = :userId
                         or exists (select 1 from GameRegistration registration
                             where registration.gameId = game.id and registration.playerId = :userId
                               and registration.status <> club.ttg.findgame.registration.RegistrationStatus.REJECTED)))
                or (:role = 'UPCOMING'
                    and (game.masterId = :userId or exists (select 1 from GameRegistration registration
                        where registration.gameId = game.id and registration.playerId = :userId
                          and registration.status = club.ttg.findgame.registration.RegistrationStatus.APPROVED))
                    and exists (select 1 from GameSession session where session.gameId = game.id
                        and session.status = club.ttg.findgame.session.GameSessionStatus.SCHEDULED
                        and session.startsAt >= :now))
              )
            order by case when :role = 'UPCOMING' then
                (select min(session.startsAt) from GameSession session where session.gameId = game.id
                    and session.status = club.ttg.findgame.session.GameSessionStatus.SCHEDULED
                    and session.startsAt >= :now) else null end asc,
                game.listPositionAt desc, game.id asc
            """)
    Page<Game> findPersonal(
            @Param("userId") UUID userId,
            @Param("statuses") Collection<GameStatus> statuses,
            @Param("role") String role,
            @Param("now") Instant now,
            Pageable pageable);

    boolean existsByMasterIdAndStatusNotAndDeletedAtIsNull(UUID masterId, GameStatus status);

    Page<Game> findAllByMasterIdAndDeletedAtIsNull(UUID masterId, Pageable pageable);

    /** Скрытые модератором игры доступны только их владельцу. */
    Page<Game> findAllByMasterIdAndDeletedAtIsNotNullAndStatusIn(
            UUID masterId, Collection<GameStatus> statuses, Pageable pageable);

    /** Активные объявления мастера для одного модераторского решения. */
    List<Game> findAllByMasterIdAndDeletedAtIsNull(UUID masterId);

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

    /** Сколько игр мастера в заданном состоянии. */
    long countByMasterIdAndStatusAndDeletedAtIsNull(UUID masterId, GameStatus status);

    /**
     * Сколько игр мастера набирают игроков прямо сейчас: открытые, с
     * незакрытым набором. Полнота стола здесь не важна — набор он закрывает
     * сам, и по счётчику видно ровно то, что мастер объявил.
     */
    long countByMasterIdAndStatusAndRecruitmentClosedFalseAndDeletedAtIsNull(
            UUID masterId, GameStatus status);

    Optional<Game> findByIdAndVisibilityAndDeletedAtIsNull(UUID id, GameVisibility visibility);

    Optional<Game> findByIdAndInviteCodeAndDeletedAtIsNull(UUID id, UUID inviteCode);

    Optional<Game> findByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from Game game where game.id = :id and game.deletedAt is null")
    Optional<Game> findByIdForUpdate(@Param("id") UUID id);

    /** Скрытую игру тоже блокирует: выборка нужна для отмены мягкого удаления. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select game from Game game where game.id = :id")
    Optional<Game> findByIdIncludingDeletedForUpdate(@Param("id") UUID id);
}
