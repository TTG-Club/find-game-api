package club.ttg.findgame.chat.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Один выпавший куб.
 *
 * @param value Что выпало.
 * @param valid Учтён ли куб в итоге: отброшенные роллером остаются видны
 *              зачёркнутыми — по ним читается, как получился итог.
 * @param critical Крит: `success`, `failure` или пусто.
 */
public record DiceRollValueRequest(
        @NotNull Integer value,
        @NotNull Boolean valid,
        @Size(max = 20) String critical
) {
}
