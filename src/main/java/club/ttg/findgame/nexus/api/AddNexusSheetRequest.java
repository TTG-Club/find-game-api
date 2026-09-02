package club.ttg.findgame.nexus.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Лист, выкладываемый в комнату.
 *
 * @param shareToken Токен общего доступа листа: по нему его открывает вся
 *                   комната, и без него лист виден только владельцу.
 * @param characterName Подпись, по которой лист узнают за столом.
 */
public record AddNexusSheetRequest(
        @NotBlank @Size(max = 255) String shareToken,
        @NotBlank @Size(max = 100) String characterName
) {
}
