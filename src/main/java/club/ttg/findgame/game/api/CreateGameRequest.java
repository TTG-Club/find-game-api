package club.ttg.findgame.game.api;

import club.ttg.findgame.common.SiteUrl;
import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameDurationType;
import club.ttg.findgame.game.GameSystem;
import club.ttg.findgame.game.GameType;
import club.ttg.findgame.game.GameVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.util.Set;

public record CreateGameRequest(
        @NotBlank @Size(max = 150)
        @Schema(example = "Проклятие Страда")
        String title,

        @NotNull
        GameSystem system,

        @Pattern(regexp = SiteUrl.PATTERN, message = SiteUrl.MESSAGE)
        @Size(max = 2048)
        @Schema(example = "/s3/games/curse-of-strahd.webp")
        String imageUrl,

        @URL @Size(max = 2048)
        @Schema(example = "https://vtt.example.org/games/curse-of-strahd")
        String virtualTableUrl,

        @URL @Size(max = 2048)
        @Schema(example = "https://t.me/master")
        String masterChatUrl,

        @URL @Size(max = 2048)
        @Schema(example = "https://t.me/+strahd-party")
        String gameChatUrl,

        @Size(max = 100)
        @Schema(example = "Готическое фэнтези")
        String genre,

        @NotBlank @Size(max = 20_000)
        String description,

        @NotBlank @Size(max = 10_000)
        String requirements,

        @Size(max = 50)
        Set<@NotBlank @Size(max = 120) String> allowedSources,

        @NotNull
        GameType type,

        @Size(max = 120)
        @Schema(example = "Москва")
        String city,

        @Size(max = 300)
        @Schema(example = "Клуб «Кубик», Пятницкая 12")
        String venue,

        @Min(1) @Max(100)
        int playersToStart,

        @Min(1) @Max(100)
        int maxPlayers,

        @Min(0) @Max(120)
        Integer minAge,

        @Min(0) @Max(120)
        Integer maxAge,

        @Min(1) @Max(20)
        int startingLevel,

        @Schema(
                description = "Разрешён ли игроку персонаж другого пола",
                example = "true",
                defaultValue = "false"
        )
        Boolean crossplayAllowed,

        @NotNull
        GameDurationType durationType,

        @NotNull
        GameCostType costType,

        @NotNull
        GameVisibility visibility
) {
}
