package club.ttg.findgame.chat.api;

import club.ttg.findgame.chat.ChatEventType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateChatEventRequest(
        @NotNull UUID clientMessageId,
        @NotNull ChatEventType type,
        @Size(max = 4000) String text,
        @Valid DiceRollRequest diceRoll,
        @Valid SpellCastRequest spellCast
) {
}
