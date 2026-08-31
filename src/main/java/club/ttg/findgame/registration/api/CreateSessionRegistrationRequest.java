package club.ttg.findgame.registration.api;

import club.ttg.findgame.common.SiteUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Заявка игрока. Лист персонажа необязателен, и приложить его можно двумя
 * способами: ссылкой или именем персонажа — у кого-то листа на сайте просто
 * нет. Оба поля независимы: игрок вправе указать и то, и другое.
 */
public record CreateSessionRegistrationRequest(
        @Pattern(regexp = SiteUrl.PATTERN, message = SiteUrl.MESSAGE)
        @Size(max = 2048)
        @Schema(example = "/tools/character-sheet/shared/9d1f1d0e")
        String characterSheetUrl,

        @Size(max = 100)
        @Schema(example = "Тассельхоф Непоседа")
        String characterName
) {
}
