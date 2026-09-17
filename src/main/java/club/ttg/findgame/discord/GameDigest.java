package club.ttg.findgame.discord;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static club.ttg.findgame.discord.PublicationModels.*;

/** Подборка разных систем из публичного каталога без приватных полей. */
@Service
public class GameDigest {
    private final JdbcTemplate jdbc;
    private final String siteUrl;

    /** Проверяет адрес сайта один раз при запуске. */
    public GameDigest(JdbcTemplate jdbc, @Value("${discord-publications.site-url:https://ttg.club}") String siteUrl) {
        this.jdbc = jdbc;
        URI uri = URI.create(siteUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getHost().length() > 60 || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("DISCORD_PUBLICATION_SITE_URL должен быть HTTPS-адресом сайта без пути");
        }
        this.siteUrl = "https://" + uri.getHost();
    }

    /** Чередует системы по кругам; внутри каждой сохраняет порядок каталога. */
    public List<GameEntry> preview() {
        return jdbc.query("""
                with eligible as (
                    select games.id, games.title, games.game_system, games.custom_system,
                           systems.name as system_name, games.max_players, games.list_position_at,
                           count(registrations.id) as taken_seats
                    from games join game_systems systems on systems.code = games.game_system
                    left join game_registrations registrations on registrations.game_id = games.id
                         and registrations.status <> 'REJECTED'
                    where games.status = 'OPEN' and games.visibility = 'PUBLIC'
                         and games.deleted_at is null and games.recruitment_closed = false
                    group by games.id, games.title, games.game_system, games.custom_system,
                             systems.name, games.max_players, games.list_position_at
                    having count(registrations.id) < games.max_players
                ), ranked as (
                    select eligible.*, row_number() over (
                        partition by game_system, case when game_system = 'HOMEBREW' then lower(trim(custom_system)) else '' end
                        order by list_position_at desc, id desc
                    ) as system_rank from eligible
                )
                select * from ranked order by system_rank, list_position_at desc, id desc limit 20
                """, (result, rowNumber) -> {
            UUID gameId = result.getObject("id", UUID.class);
            String system = "HOMEBREW".equals(result.getString("game_system"))
                    ? result.getString("custom_system") : result.getString("system_name");
            return new GameEntry(gameId, result.getString("title"), system, result.getInt("taken_seats"),
                    result.getInt("max_players"), siteUrl + "/games/" + gameId);
        });
    }

    /** Создаёт одно компактное сообщение в пределах лимита Discord в 6000 символов. */
    Map<String, Object> payload(List<GameEntry> games) {
        List<Map<String, Object>> fields = games.stream().map(game -> Map.<String, Object>of(
                "name", compact(game.title(), 70),
                "value", compact(game.system(), 35) + " · Занято " + game.takenSeats() + "/" + game.maxPlayers()
                        + " · Свободно " + (game.maxPlayers() - game.takenSeats())
                        + "\n[Подробнее на сайте](" + game.url() + ")",
                "inline", false)).toList();
        return Map.of("allowed_mentions", Map.of("parse", List.of()), "embeds", List.of(Map.of(
                "title", "Игры с открытым набором", "url", siteUrl + "/games", "fields", fields)));
    }

    /** Убирает разметку и управляющие символы из пользовательских названий. */
    static String compact(String text, int maximum) {
        String cleaned = text.replaceAll("[\\p{Cntrl}\\p{Cf}*_`~|<>\\[\\]()]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return "Игра";
        if (cleaned.length() <= maximum) return cleaned;
        int end = maximum - 1;
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))) end--;
        return cleaned.substring(0, end) + "…";
    }
}
