package club.ttg.findgame.session.api;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CopyGameSessionRequest(
        @Size(max = 150)
        @Pattern(regexp = ".*\\S.*", message = "не должно быть пустой строкой")
        String title,

        @NotNull @FutureOrPresent
        Instant startsAt
) {
}
