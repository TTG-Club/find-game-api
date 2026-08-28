package club.ttg.findgame.game.api;

import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameDurationType;
import club.ttg.findgame.game.GameStatus;
import club.ttg.findgame.game.GameSystem;
import club.ttg.findgame.game.GameType;
import club.ttg.findgame.game.GameVisibility;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record GameResponse(
        UUID id,
        UUID masterId,
        String title,
        GameSystem system,
        String imageUrl,
        String virtualTableUrl,
        String genre,
        String description,
        String requirements,
        Set<String> allowedSources,
        GameType type,
        String city,
        int playersToStart,
        int maxPlayers,
        Integer minAge,
        Integer maxAge,
        int startingLevel,
        boolean crossplayAllowed,
        GameStatus status,
        GameDurationType durationType,
        GameCostType costType,
        GameVisibility visibility,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID inviteCode,
        Instant createdAt,
        Instant listPositionAt,
        Instant updatedAt
) {
}
