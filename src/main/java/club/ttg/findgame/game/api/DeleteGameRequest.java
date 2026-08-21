package club.ttg.findgame.game.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeleteGameRequest(
        @NotBlank @Size(max = 1000) String reason
) {
}
