package club.ttg.findgame.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Одна жалоба пользователя на конкретное объявление. */
@Entity
@Table(
        name = "game_reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_game_reports_game_reporter",
                columnNames = {"game_id", "reporter_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameReport {

    @Id
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GameReportReason reason;

    @Column(length = 1000)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public GameReport(UUID gameId, UUID reporterId, GameReportReason reason, String details) {
        this.gameId = gameId;
        this.reporterId = reporterId;
        this.reason = reason;
        this.details = details;
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
