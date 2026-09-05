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
 * Вердикт одного участника встречи о другом.
 *
 * Оценка простая — «сыграл бы снова» или нет: на малых числах это честнее
 * пятизвёздочной шкалы, которая быстро схлопывается в сплошные пятёрки.
 */
@Entity
@Table(name = "session_reviews")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionReview {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

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

    /** Отметка завершения встречи: по ней считается окно на оценку. */
    @Column(name = "session_completed_at", nullable = false)
    private Instant sessionCompletedAt;

    /**
     * Момент раскрытия: ставится, когда высказалась вторая сторона. Пока пусто
     * и окно не вышло, оценку видит только автор.
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
