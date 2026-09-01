package club.ttg.findgame.nexus.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Новая самостоятельная комната.
 *
 * @param title Название, по которому владелец узнает её в списке.
 */
public record CreateNexusRequest(
        @NotBlank @Size(max = 150) String title
) {
}
