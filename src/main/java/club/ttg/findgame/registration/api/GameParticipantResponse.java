package club.ttg.findgame.registration.api;

import java.util.UUID;

/** Состав игры без приватных сведений из заявок. */
public record GameParticipantResponse(UUID playerId, String characterName) {}
