package club.ttg.findgame.nexus.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Идущий бой глазами комнаты.
 *
 * @param trackerId Бой в core-api.
 * @param title Название боя в комнате.
 * @param round Номер раунда.
 * @param active Идёт ли бой.
 * @param currentParticipantId Чей сейчас ход; пусто — ход ещё не передан.
 * @param participants Состав в порядке хода.
 * @param updatedAt Когда мастер обновлял снимок.
 */
public record FightStateResponse(
        UUID trackerId,
        String title,
        int round,
        boolean active,
        String currentParticipantId,
        List<FightStateRequest.FightParticipantRequest> participants,
        Instant updatedAt
) {
}
