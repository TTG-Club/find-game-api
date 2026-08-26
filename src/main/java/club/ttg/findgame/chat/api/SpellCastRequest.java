package club.ttg.findgame.chat.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SpellCastRequest(
        @Size(max = 100) String spellId,
        @NotBlank @Size(max = 200) String name,
        @Min(0) @Max(9) Integer level,
        @Size(max = 300) String target
) {
}
