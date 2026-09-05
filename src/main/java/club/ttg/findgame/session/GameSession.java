package club.ttg.findgame.session;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "game_sessions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameSession {

    @Id
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(nullable = false, length = 150)
    private String title;

    /**
     * Дата и время начала. Пусто у набора с открытой датой: мастер назначает
     * его после того, как соберёт игроков.
     */
    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "estimated_duration_minutes")
    private Integer estimatedDurationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private GameSessionStatus status;

    /**
     * Когда встречу объявили завершённой. Дата самой встречи для этого не
     * годится: мастер закрывает сессию тогда, когда она действительно кончилась.
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "price_amount", precision = 12, scale = 2)
    private BigDecimal priceAmount;

    @Column(name = "price_currency", length = 3)
    private String priceCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 20)
    private SessionPaymentType paymentType;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
