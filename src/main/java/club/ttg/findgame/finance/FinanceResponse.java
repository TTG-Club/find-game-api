package club.ttg.findgame.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ответ фильтруется сервером: игрок получает только собственный счёт. */
public record FinanceResponse(List<Account> accounts) {
    public record Account(UUID playerId, Map<String, BigDecimal> balances,
                          List<FinanceEntry> entries, List<Bill> bills) {}
    public record Bill(UUID sessionId, String title, Instant startsAt, String currency,
                       BigDecimal amount, BigDecimal remaining, BigDecimal claimed,
                       boolean finalized, boolean exempt) {}
}
