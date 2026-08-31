package club.ttg.findgame.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameSessionRepository extends JpaRepository<GameSession, UUID> {

    boolean existsByGameId(UUID gameId);

    /**
     * Сессии игры по возрастанию даты. Наборы с открытой датой (`starts_at`
     * пуст) идут в конец: у них времени ещё нет, и ставить их перед
     * назначенными значило бы прятать ближайшую игру под ними.
     * Порядок между самими открытыми задаёт `id` — иначе он был бы случайным.
     */
    @Query("""
            select session from GameSession session
            where session.gameId = :gameId
            order by session.startsAt asc nulls last, session.id asc
            """)
    List<GameSession> findAllByGameIdOrderByStartsAtAsc(@Param("gameId") UUID gameId);

    /**
     * Предстоящие сессии перечисленных игр в порядке близости. Набор с
     * открытой датой тоже предстоящий: время у него ещё не назначено, но
     * набор идёт, поэтому он попадает в выдачу и встаёт после датированных.
     *
     * Из этого списка карточка каталога берёт ближайшую сессию каждой игры —
     * ту самую, в которую игрок и будет подавать заявку.
     */
    @Query("""
            select session from GameSession session
            where session.gameId in :gameIds
              and session.status = :status
              and (session.startsAt is null or session.startsAt >= :from)
            order by session.startsAt asc nulls last, session.id asc
            """)
    List<GameSession> findUpcoming(
            @Param("gameIds") Collection<UUID> gameIds,
            @Param("status") GameSessionStatus status,
            @Param("from") Instant from
    );

    Optional<GameSession> findByIdAndGameId(UUID id, UUID gameId);
}
