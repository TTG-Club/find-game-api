package club.ttg.findgame.registration;

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
 * Участие игрока в сессии.
 *
 * Само по себе оно ничего не решает: состав определяет заявка в игру, а эта
 * запись заводится сервисом для каждого принятого игрока и хранит то, что
 * относится к конкретной встрече, — присутствие и оплату. Поэтому у неё нет
 * ни статуса, ни листа персонажа: они принадлежат заявке.
 */
@Entity
@Table(name = "game_session_registrations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionRegistration {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_status", length = 20)
    private SessionAttendanceStatus attendanceStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    /**
     * Заводит участие игрока в сессии. Присутствие по умолчанию — «не буду»:
     * его подтверждает сам игрок, и молчание нельзя считать согласием.
     */
    public static SessionRegistration of(UUID sessionId, UUID playerId) {
        SessionRegistration participation = new SessionRegistration();
        participation.sessionId = sessionId;
        participation.playerId = playerId;
        participation.attendanceStatus = SessionAttendanceStatus.NOT_ATTENDING;

        return participation;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }

        Instant now = Instant.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
