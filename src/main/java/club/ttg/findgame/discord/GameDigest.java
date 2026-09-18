package club.ttg.findgame.discord;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static club.ttg.findgame.discord.PublicationModels.*;

/** Подборка разных систем из публичного каталога без приватных полей. */
@Service
public class GameDigest {
    static final int MAX_GAMES = 10;
    static final int MAX_GENRES_LENGTH = 80;
    static final int MAX_DESCRIPTION_LENGTH = 600;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int SUPPRESS_EMBEDS = 4;
    private final JdbcTemplate jdbc;
    private final String siteUrl;
    private final ObjectMapper mapper;
    private final String heading;

    /** Проверяет адрес сайта один раз при запуске. */
    public GameDigest(JdbcTemplate jdbc, ObjectMapper mapper, @Value("${discord-publications.site-url}") String siteUrl) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        URI uri = URI.create(siteUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getHost().length() > 60 || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("DISCORD_PUBLICATION_SITE_URL должен быть HTTPS-адресом сайта без пути");
        }
        this.siteUrl = "https://" + uri.getHost();
        this.heading = "Игры с открытым набором на сайте " + uri.getHost();
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
                ), selected as (
                    select * from ranked order by system_rank, list_position_at desc, id desc limit ?
                )
                select selected.*, details.description, details.custom_genre,
                       (select string_agg(genre.name, ', ' order by genre.name)
                        from game_genres link join genres genre on genre.id = link.genre_id
                        where link.game_id = selected.id) as genre_names
                from selected join games details on details.id = selected.id
                order by selected.system_rank, selected.list_position_at desc, selected.id desc
                """, (result, rowNumber) -> {
            UUID gameId = result.getObject("id", UUID.class);
            String system = "HOMEBREW".equals(result.getString("game_system"))
                    ? result.getString("custom_system") : result.getString("system_name");
            String genres = Stream.of(result.getString("genre_names"), result.getString("custom_genre"))
                    .filter(genre -> genre != null && !genre.isBlank()).collect(Collectors.joining(", "));
            return new GameEntry(gameId, result.getString("title"), system, result.getInt("taken_seats"),
                    result.getInt("max_players"), siteUrl + "/games/" + gameId,
                    DigestText.compact(genres, MAX_GENRES_LENGTH),
                    DigestText.description(result.getString("description"), mapper, MAX_DESCRIPTION_LENGTH));
        }, MAX_GAMES);
    }

    /** Делит подборку по границам игр на обычные сообщения до 2000 символов. */
    List<DigestMessage> messages(List<GameEntry> games) {
        List<DigestMessage> messages = new ArrayList<>();
        StringBuilder content = new StringBuilder(heading);
        int gameCount = 0;
        for (GameEntry game : games.stream().limit(MAX_GAMES).toList()) {
            String block = compact(game.title(), 70) + "\n" + gameDetails(game);
            if (gameCount > 0 && content.length() + 2 + block.length() > MAX_MESSAGE_LENGTH) {
                messages.add(message(content.toString(), gameCount));
                content = new StringBuilder(heading);
                gameCount = 0;
            }
            content.append("\n\n").append(block);
            gameCount++;
        }
        if (gameCount > 0) messages.add(message(content.toString(), gameCount));
        return List.copyOf(messages);
    }

    /** Отключает карточки ссылок и упоминания для каждого сообщения. */
    private static DigestMessage message(String content, int gameCount) {
        return new DigestMessage(Map.of("content", content, "flags", SUPPRESS_EMBEDS,
                "allowed_mentions", Map.of("parse", List.of())), gameCount);
    }

    /** Дополняет число участников жанрами и коротким текстом; пустые строки не публикует. */
    private static String gameDetails(GameEntry game) {
        String genres = DigestText.compact(game.genreSummary(), MAX_GENRES_LENGTH);
        String description = DigestText.compact(game.description(), MAX_DESCRIPTION_LENGTH);
        return compact(game.system(), 35) + " · Занято " + game.takenSeats() + "/" + game.maxPlayers()
                + " · Свободно " + (game.maxPlayers() - game.takenSeats())
                + (genres.isBlank() ? "" : "\nЖанры: " + genres)
                + (description.isBlank() ? "" : "\n> " + description)
                + "\n\n[Подробнее на сайте](" + game.url() + ")";
    }

    /** Убирает разметку и управляющие символы из пользовательских названий. */
    static String compact(String text, int maximum) {
        String cleaned = DigestText.compact(text, maximum);
        return cleaned.isEmpty() ? "Игра" : cleaned;
    }
}
