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

    @Column(name = "character_sheet_url", length = 2048)
    private String characterSheetUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionRegistrationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_status", length = 20)
    private SessionAttendanceStatus attendanceStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    public static SessionRegistration copyApprovedTo(
            UUID targetSessionId,
            SessionRegistration source
    ) {
        SessionRegistration copy = new SessionRegistration();
        copy.setSessionId(targetSessionId);
        copy.setPlayerId(source.getPlayerId());
        copy.setCharacterSheetUrl(source.getCharacterSheetUrl());
        copy.setStatus(SessionRegistrationStatus.APPROVED);
        copy.setAttendanceStatus(SessionAttendanceStatus.NOT_ATTENDING);
        return copy;
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
