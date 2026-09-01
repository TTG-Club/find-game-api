package club.ttg.findgame.nexus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Игровая комната группы: чат, листы персонажей, инициатива, общий лут и
 * выдача магических предметов.
 *
 * Комната бывает двух происхождений. Самостоятельную заводит владелец и зовёт
 * в неё ссылкой — перешедший по ссылке входит в состав сам. Комната игры
 * привязана к ней и в состав пускает только подавших заявку; ссылкой она не
 * зовёт, и в общем списке комнат её нет — туда попадают со страницы игры.
 */
@Entity
@Table(name = "nexuses")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Nexus {

    @Id
    private UUID id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** Код приглашения самостоятельной комнаты; у комнаты игры его нет. */
    @Column(name = "invite_code")
    private UUID inviteCode;

    /** Игра, чью комнату описывает запись; {@code null} — самостоятельная. */
    @Column(name = "game_id")
    private UUID gameId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Комната игры: состав берётся из заявок, а не из своего списка. */
    public boolean isGameRoom() {
        return gameId != null;
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

        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
