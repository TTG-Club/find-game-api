package club.ttg.findgame.chat.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DiceRollRequest(
        @NotBlank @Size(max = 40) String expression,
        @Size(max = 200) String label
) {
}
