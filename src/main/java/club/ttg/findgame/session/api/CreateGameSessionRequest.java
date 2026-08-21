package club.ttg.findgame.session.api;

import club.ttg.findgame.session.SessionPaymentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateGameSessionRequest(
        @NotBlank @Size(max = 150) String title,
        @NotNull @FutureOrPresent Instant startsAt,
        @Positive Integer estimatedDurationMinutes,
        @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal priceAmount,
        @Pattern(regexp = "[A-Z]{3}", message = "должно содержать трёхбуквенный код валюты ISO 4217")
        String priceCurrency,
        SessionPaymentType paymentType
) {
}
