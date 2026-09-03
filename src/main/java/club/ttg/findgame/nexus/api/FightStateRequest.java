package club.ttg.findgame.nexus.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Снимок идущего боя, который кладёт в комнату клиент мастера.
 *
 * Трекер открыт только тому, кто ведёт игру, поэтому очередь ходов приходит
 * сюда пересказом — ровно тем, что за столом и так лежит на виду. Что попадёт
 * в снимок, решает клиент мастера: существа приходят безымянными, а их запас
 * хитов не приходит вовсе.
 *
 * @param trackerId Бой в core-api, к которому относится снимок.
 * @param round Номер раунда.
 * @param active Идёт ли бой: в подготовке карусель комнате не нужна.
 * @param currentParticipantId Чей сейчас ход; пусто — ход ещё не передан.
 * @param participants Состав в порядке хода.
 */
public record FightStateRequest(
        @NotNull UUID trackerId,
        @Positive int round,
        boolean active,
        @Size(max = 64) String currentParticipantId,
        @NotNull @Size(max = 60) List<@Valid FightParticipantRequest> participants
) {

    /**
     * Боец в снимке.
     *
     * @param id Идентификатор участника в трекере — по нему находят текущего.
     * @param name Имя для карусели.
     * @param player Персонаж игрока или существо мастера.
     * @param dead Повержен: в карусели гаснет, но места не теряет.
     * @param avatarUrl Картинка токена; пусто — иконка по типу.
     * @param color Цвет токена, выбранный мастером.
     */
    public record FightParticipantRequest(
            @NotBlank @Size(max = 64) String id,
            @NotBlank @Size(max = 150) String name,
            boolean player,
            boolean dead,
            @Size(max = 500) String avatarUrl,
            @Size(max = 30) String color
    ) {
    }
}
