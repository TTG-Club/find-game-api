package club.ttg.findgame.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameReviewRepository extends JpaRepository<GameReview, UUID> {

    Optional<GameReview> findByGameIdAndAuthorIdAndTargetId(
            UUID gameId, UUID authorId, UUID targetId);

    /** Оценки, написанные автором в игре: их он видит всегда. */
    List<GameReview> findAllByGameIdAndAuthorId(UUID gameId, UUID authorId);

    /** Все оценки игры: по ним видно, кому ещё есть кого оценить. */
    List<GameReview> findAllByGameId(UUID gameId);

    /** Оценки автора сразу по нескольким играм — для списка ждущих оценки. */
    List<GameReview> findAllByAuthorIdAndGameIdIn(UUID authorId, Collection<UUID> gameIds);

    /**
     * Раскрытые отзывы об участнике, свежие первыми.
     *
     * Раскрытым отзыв становится, когда высказалась вторая сторона либо когда
     * вышел срок ожидания её ответа: до этого показывать его нельзя —
     * увидевший первым ответил бы тем же.
     */
    @Query("""
            select review from GameReview review
            where review.targetId = :targetId
              and review.kind = :kind
              and review.hiddenAt is null
              and (review.visibleAt is not null or review.createdAt < :revealEdge)
            order by review.updatedAt desc
            """)
    List<GameReview> findVisible(
            @Param("targetId") UUID targetId,
            @Param("kind") ReviewKind kind,
            @Param("revealEdge") Instant revealEdge);
}
