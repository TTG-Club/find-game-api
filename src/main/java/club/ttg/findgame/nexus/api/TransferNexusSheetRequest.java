package club.ttg.findgame.nexus.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Передача листа другому участнику.
 *
 * @param ownerId Кому переходит лист: он сможет убрать его из комнаты.
 */
public record TransferNexusSheetRequest(
        @NotNull UUID ownerId
) {
}
