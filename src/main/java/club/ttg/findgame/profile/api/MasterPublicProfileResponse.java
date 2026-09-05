package club.ttg.findgame.profile.api;

import java.util.UUID;

/**
 * Мастер глазами того, кто выбирает игру.
 *
 * Отдаёт только то, что мастер сам о себе написал, и счётчики его игр: по ним
 * видно, водит ли он вообще или объявления копятся без исхода. Возраст, пол и
 * прочее из профиля сюда не идут — их владелец никому не показывал.
 *
 * @param userId Мастер.
 * @param about О себе как о мастере.
 * @param tabletopExperienceYears Стаж за столом, лет; {@code null} — не указан.
 * @param recruitingGames Игр в наборе прямо сейчас.
 * @param closedGames Игр доведено до конца.
 * @param cancelledGames Игр не состоялось.
 * @param completedSessions Проведённых встреч.
 * @param recommended Сколько игроков сыграли бы с ним снова.
 * @param reviews Сколько всего раскрытых оценок.
 */
public record MasterPublicProfileResponse(
        UUID userId,
        String about,
        Integer tabletopExperienceYears,
        long recruitingGames,
        long closedGames,
        long cancelledGames,
        long completedSessions,
        long recommended,
        long reviews
) {
}
