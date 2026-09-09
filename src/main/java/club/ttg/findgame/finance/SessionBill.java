package club.ttg.findgame.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Расчёт за одну сессию: начисление отделено от подтверждённой оплаты. */
@Entity
@Table(name = "game_session_bills", uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "player_id"}))
@Getter
@Setter
@NoArgsConstructor
public class SessionBill {
    @Id private UUID id = UUID.randomUUID();
    @Column(nullable = false) private UUID gameId;
    @Column(nullable = false) private UUID sessionId;
    @Column(nullable = false) private UUID playerId;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal booked = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal settled = BigDecimal.ZERO;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal claimed = BigDecimal.ZERO;
    @Column(nullable = false) private boolean finalized;
    @Column(nullable = false) private boolean exempt;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    /** Остаток к оплате без отрицательных сумм. */
    public BigDecimal remaining() {
        return exempt ? BigDecimal.ZERO : amount.subtract(settled).max(BigDecimal.ZERO);
    }
}
