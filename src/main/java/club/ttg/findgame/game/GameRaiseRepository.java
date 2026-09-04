package club.ttg.findgame.game;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GameRaiseRepository extends JpaRepository<GameRaise, UUID> {

    /** Сколько раз игру поднимали начиная с указанного момента. */
    long countByGameIdAndRaisedAtAfter(UUID gameId, Instant since);

    /**
     * Поднятия игры за окно, самое раннее первым: по нему считается, когда
     * освободится место в суточной норме.
     */
    @Query("""
            select raise from GameRaise raise
            where raise.gameId = :gameId and raise.raisedAt > :since
            order by raise.raisedAt asc
            """)
    List<GameRaise> findWindow(
            @Param("gameId") UUID gameId,
            @Param("since") Instant since,
            Limit limit);
}
