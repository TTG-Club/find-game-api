package club.ttg.findgame.profile.api;

import java.util.UUID;

/**
 * Игрок глазами тех, с кем он сидит за одним столом.
 *
 * Отдаёт только то, что игрок сам о себе написал, и число встреч, на которые
 * он был записан: по нему видно, часто ли он играет. Возраст, пол и прочее из
 * профиля сюда не идут — их владелец никому не показывал.
 *
 * Репутации и отзывов здесь нет намеренно: оценки игроков — разговор мастеров
 * между собой, и мастер читает их через заявку в свою игру, а не отсюда.
 *
 * @param userId Игрок.
 * @param about О себе как об игроке.
 * @param tabletopExperienceYears Стаж за столом, лет; {@code null} — не указан.
 * @param playedSessions На скольких состоявшихся встречах он был в составе.
 */
public record PlayerPublicProfileResponse(
        UUID userId,
        String about,
        Integer tabletopExperienceYears,
        long playedSessions
) {
}
