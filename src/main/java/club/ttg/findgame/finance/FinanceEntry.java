package club.ttg.findgame.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Неизменяемая запись учёта; реальные переводы сервис не выполняет. */
@Entity
@Table(name = "game_finance_entries")
@Getter
@NoArgsConstructor
public class FinanceEntry {
    @Id private UUID id;
    @Column(nullable = false) private UUID gameId;
    @Column(nullable = false) private UUID playerId;
    private UUID sessionId;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 30) private String kind;
    @Column(length = 500) private String comment;
    @Column(nullable = false) private UUID actorId;
    @Column(nullable = false) private Instant createdAt;

    /** Создаёт операцию с ключом повторного запроса. */
    public FinanceEntry(UUID id, UUID gameId, UUID playerId, UUID sessionId, BigDecimal amount,
                        String currency, String kind, String comment, UUID actorId) {
        this.id = id;
        this.gameId = gameId;
        this.playerId = playerId;
        this.sessionId = sessionId;
        this.amount = amount;
        this.currency = currency;
        this.kind = kind;
        this.comment = comment;
        this.actorId = actorId;
        this.createdAt = Instant.now();
    }
}
