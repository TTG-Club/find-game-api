package club.ttg.findgame.game.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateGameSystemRequest(
        @NotBlank
        @Size(max = 30)
        @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]*")
        @Schema(example = "PATHFINDER_2E")
        String code,

        @NotBlank
        @Size(max = 120)
        @Schema(example = "Pathfinder 2e")
        String name
) {
}
