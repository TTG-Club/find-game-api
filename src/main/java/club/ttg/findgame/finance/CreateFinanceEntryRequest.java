package club.ttg.findgame.finance;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateFinanceEntryRequest(
        @NotNull UUID operationId,
        @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Pattern(regexp = "TOP_UP|ADJUSTMENT") String kind,
        @Size(max = 500) String comment) {}
