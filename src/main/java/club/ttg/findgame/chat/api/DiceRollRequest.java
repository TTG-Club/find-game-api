package club.ttg.findgame.chat.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

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
 * @param groups Что выпало на кубах, по группам одного вида.
 * @param subject Чем бросали: оружие, навык, характеристика.
 * @param label Что за бросок: атака, урон, проверка.
 */
public record DiceRollRequest(
        @NotBlank @Size(max = 100) String expression,
        @NotNull Integer total,
        @Size(max = 20) @Valid List<DiceGroupRequest> groups,
        @Size(max = 200) String subject,
        @Size(max = 200) String label
) {
}
