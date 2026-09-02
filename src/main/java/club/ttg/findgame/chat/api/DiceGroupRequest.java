package club.ttg.findgame.chat.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Группа кубов одного вида внутри броска: «2к6» в формуле «2к6+1к4».
 *
 * @param label Подпись группы, как её показывает роллер.
 * @param rolls Что выпало на кубах группы.
 */
public record DiceGroupRequest(
        @Size(max = 40) String label,
        @NotEmpty @Size(max = 100) @Valid List<DiceRollValueRequest> rolls
) {
}
