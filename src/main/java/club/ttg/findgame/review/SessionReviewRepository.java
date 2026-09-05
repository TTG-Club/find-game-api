package club.ttg.findgame.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionReviewRepository extends JpaRepository<SessionReview, UUID> {

    Optional<SessionReview> findBySessionIdAndAuthorIdAndTargetId(
            UUID sessionId, UUID authorId, UUID targetId);

    /** Оценки, написанные автором за встречу: их он видит всегда. */
    List<SessionReview> findAllBySessionIdAndAuthorId(UUID sessionId, UUID authorId);

    /**
     * Раскрытые отзывы об участнике, свежие первыми.
     *
     * Раскрытым отзыв становится, когда высказалась вторая сторона либо когда
     * вышло окно: до этого показывать его нельзя — увидевший первым ответил бы
     * тем же.
     */
    @Query("""
            select review from SessionReview review
            where review.targetId = :targetId
              and review.kind = :kind
              and review.hiddenAt is null
              and (review.visibleAt is not null or review.sessionCompletedAt < :windowEdge)
            order by review.createdAt desc
            """)
    List<SessionReview> findVisible(
            @Param("targetId") UUID targetId,
            @Param("kind") ReviewKind kind,
            @Param("windowEdge") Instant windowEdge);

    /** Сколько всего раскрытых отзывов об участнике. */
    @Query("""
            select count(review) from SessionReview review
            where review.targetId = :targetId
              and review.kind = :kind
              and review.hiddenAt is null
              and (review.visibleAt is not null or review.sessionCompletedAt < :windowEdge)
            """)
    long countVisible(
            @Param("targetId") UUID targetId,
            @Param("kind") ReviewKind kind,
            @Param("windowEdge") Instant windowEdge);

    /** Сколько из них — «сыграл бы снова». */
    @Query("""
            select count(review) from SessionReview review
            where review.targetId = :targetId
              and review.kind = :kind
              and review.recommended = true
              and review.hiddenAt is null
              and (review.visibleAt is not null or review.sessionCompletedAt < :windowEdge)
            """)
    long countVisibleRecommended(
            @Param("targetId") UUID targetId,
            @Param("kind") ReviewKind kind,
            @Param("windowEdge") Instant windowEdge);
}
