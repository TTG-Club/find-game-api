package club.ttg.findgame.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Уведомление участника поиска игр.
 *
 * Названия игры и сессии хранятся копией, а не читаются по ссылке: лента
 * должна оставаться читаемой и после того, как игру переименуют или удалят,
 * и собираться одним запросом.
 */
@Entity
@Table(name = "find_game_notifications")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    private UUID id;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "game_title", nullable = false, length = 150)
    private String gameTitle;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "session_title", length = 150)
    private String sessionTitle;

    /** Прочитано; пусто — ещё нет. */
    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Notification of(
            UUID recipientId,
            NotificationType type,
            UUID gameId,
            String gameTitle,
            UUID sessionId,
            String sessionTitle
    ) {
        Notification notification = new Notification();
        notification.recipientId = recipientId;
        notification.type = type;
        notification.gameId = gameId;
        notification.gameTitle = gameTitle;
        notification.sessionId = sessionId;
        notification.sessionTitle = sessionTitle;

        return notification;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
