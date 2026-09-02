package club.ttg.findgame.chat.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Готовый бросок.
 *
 * Сервис его не пересчитывает: нотация сайта — «2к20вл1», скобки, перебросы —
 * живёт в роллере браузера, и второй такой же разбор на сервере неизбежно
 * разошёлся бы с ним в мелочах. За столом бросок и так держится на доверии
 * группы, а комната закрыта для посторонних.
 *
 * @param expression Формула, как её набрал игрок.
 * @param total Итог броска.
 * @param detail Разбор броска строкой: что выпало на кубах.
 * @param label Подпись броска.
 */
public record DiceRollRequest(
        @NotBlank @Size(max = 100) String expression,
        @NotNull Integer total,
        @Size(max = 500) String detail,
        @Size(max = 200) String label
) {
}
