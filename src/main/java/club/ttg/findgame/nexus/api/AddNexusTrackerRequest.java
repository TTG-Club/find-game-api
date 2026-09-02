package club.ttg.findgame.nexus.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Трекер, заводимый в комнате.
 *
 * @param trackerId Идентификатор трекера в core-api: сам бой ведётся там.
 * @param title Название, под которым трекер виден в комнате.
 */
public record AddNexusTrackerRequest(
        @NotNull UUID trackerId,
        @NotBlank @Size(max = 150) String title
) {
}
