package club.ttg.findgame.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Вердикт одного участника игры о другом.
 *
 * Оценка простая — «сыграл бы снова» или нет: на малых числах это честнее
 * пятизвёздочной шкалы, которая быстро схлопывается в сплошные пятёрки.
 *
 * Одна оценка на пару в игре, а не на каждую встречу: кампания из сотни
 * сессий даёт один голос, и мастеру не нужно вести расписание на сайте,
 * чтобы его могли оценить.
 */
@Entity
@Table(name = "game_reviews")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameReview {

    @Id
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewKind kind;

    @Column(nullable = false)
    private boolean recommended;

    @Column(columnDefinition = "text")
    private String comment;

    /**
     * Момент раскрытия: ставится, когда высказалась вторая сторона. Пока пусто
     * и срок ожидания ответа не вышел, оценку видит только автор.
     */
    @Column(name = "visible_at")
    private Instant visibleAt;

    /** Скрыт модератором: история остаётся, из выдачи отзыв уходит. */
    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Пара для одной игры: кто, кого и в каком направлении оценивает. */
    public static GameReview of(UUID gameId, UUID authorId, UUID targetId, ReviewKind kind) {
        GameReview review = new GameReview();
        review.gameId = gameId;
        review.authorId = authorId;
        review.targetId = targetId;
        review.kind = kind;

        return review;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();

        if (id == null) {
            id = UUID.randomUUID();
        }

        if (createdAt == null) {
            createdAt = now;
        }

        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
