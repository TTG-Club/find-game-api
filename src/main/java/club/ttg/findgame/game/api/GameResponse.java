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
        /**
         * Сколько мест занято в ближайшей предстоящей сессии. Место занимает
         * любая неотклонённая заявка, включая ещё не разобранную мастером.
         * Вместе с {@code maxPlayers} и {@code playersToStart} даёт занятость
         * мест для карточки каталога.
         */
        int takenSeats,
        /**
         * Сколько из занятых мест мастер подтвердил. Разница с
         * {@code takenSeats} — заявки, которые он ещё не разобрал.
         */
        int approvedSeats,
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

    /**
     * Тот же ответ без кода приглашения: он принадлежит только владельцу игры,
     * а публичная выдача отдаёт игру всем.
     */
    public GameResponse copyWithoutInviteCode() {
        return new GameResponse(
                id, masterId, title, system, imageUrl, virtualTableUrl, genre, description, requirements,
                allowedSources, type, city, playersToStart, maxPlayers, takenSeats, approvedSeats, minAge,
                maxAge, startingLevel, crossplayAllowed, status, durationType, costType, visibility, null,
                createdAt, listPositionAt, updatedAt);
    }
}
