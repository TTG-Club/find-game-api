package club.ttg.findgame.publications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Подборка разных систем из публичного каталога без приватных полей. */
@Service
public class GameDigest {
    static final int MAX_GAMES = 8;
    static final int MAX_GENRES_LENGTH = 80;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int SUPPRESS_EMBEDS = 4;
    private final JdbcTemplate jdbc;
    private final String siteUrl;
    private final String introduction;

    /** Проверяет адрес сайта один раз при запуске. */
    public GameDigest(JdbcTemplate jdbc, @Value("${discord-publications.site-url}") String siteUrl) {
        this.jdbc = jdbc;
        URI uri = URI.create(siteUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getHost().length() > 60 || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("DISCORD_PUBLICATION_SITE_URL должен быть HTTPS-адресом сайта без пути");
        }
        this.siteUrl = "https://" + uri.getHost();
        this.introduction = "Ищете компанию для приключений? 🎲\n\n"
                + "Собрали для вас игры с открытым набором на " + uri.getHost() + ". "
                + "Выбирайте приключение по душе и знакомьтесь с будущей командой!";
    }

    /** Чередует системы по кругам; внутри каждой сохраняет порядок каталога. */
    public List<GameEntry> preview() {
        List<GameEntry> selected = jdbc.query("""
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
                select selected.*, details.custom_genre,
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
                    DigestText.compact(genres, MAX_GENRES_LENGTH), "");
        }, MAX_GAMES);
        return fit(selected);
    }

    /** Собирает один выпуск: Discord до 2000 символов, Telegram с экранированными ссылками, VK обычным текстом. */
    List<DigestMessage> messages(List<GameEntry> games, Platform platform) {
        List<GameEntry> selected = fit(games);
        if (selected.isEmpty()) return List.of();
        StringBuilder content = new StringBuilder(platform == Platform.TELEGRAM ? HtmlUtils.htmlEscapeDecimal(introduction) : introduction);
        for (GameEntry game : selected) {
            content.append("\n\n");
            switch (platform) {
                case TELEGRAM -> content.append(HtmlUtils.htmlEscapeDecimal(gameDetails(game))).append("\n<a href=\"")
                        .append(HtmlUtils.htmlEscapeDecimal(game.url())).append("\">Подробнее</a>");
                // Запись VK не поддерживает разметку ссылок, но делает кликабельным любой адрес в тексте.
                case VK -> content.append(withoutMentions(gameDetails(game))).append("\nПодробнее: ").append(game.url());
                case DISCORD -> content.append(gameBlock(game));
            }
        }
        content.append("\n\n").append(catalogueInvitation(platform));
        return List.of(switch (platform) {
            case TELEGRAM -> new DigestMessage(Map.of("text", content.toString(), "parse_mode", "HTML",
                    "link_preview_options", Map.of("is_disabled", true)), selected.size());
            case VK -> new DigestMessage(Map.of("message", content.toString()), selected.size());
            case DISCORD -> message(content.toString(), selected.size());
        });
    }

    /** Умещает сведения всех игр в одно сообщение, сохраняя полные ссылки и число мест. */
    private List<GameEntry> fit(List<GameEntry> games) {
        List<GameEntry> selected = games.stream().limit(MAX_GAMES)
                .map(game -> fitFields(game, MAX_GENRES_LENGTH)).toList();
        if (selected.isEmpty()) return selected;
        if (baseLength(selected) > MAX_MESSAGE_LENGTH) {
            int fieldsLength = selected.stream().mapToInt(game ->
                    game.title().length() + game.system().length() + game.genreSummary().length()).sum();
            int remaining = MAX_MESSAGE_LENGTH - (baseLength(selected) - fieldsLength);
            // У каждой игры три переменных поля: название, система и жанры.
            int fieldLength = remaining / (3 * selected.size());
            if (fieldLength < 2) throw new IllegalArgumentException("Ссылки подборки превышают лимит Discord");
            selected = selected.stream().map(game -> fitFields(game, fieldLength)).toList();
        }
        return selected;
    }

    /** Ограничивает пользовательские поля, сохраняя число мест и полную ссылку. */
    private static GameEntry fitFields(GameEntry game, int maximum) {
        return new GameEntry(game.id(), compact(game.title(), Math.min(70, maximum)),
                compact(game.system(), Math.min(35, maximum)), game.takenSeats(), game.maxPlayers(), game.url(),
                DigestText.compact(game.genreSummary(), Math.min(MAX_GENRES_LENGTH, maximum)), "");
    }

    /** Считает точную длину вступления, сведений об играх и приглашения в каталог. */
    private int baseLength(List<GameEntry> games) {
        return introduction.length() + games.stream().mapToInt(game -> 2 + gameBlock(game).length()).sum()
                + 2 + catalogueInvitation(Platform.DISCORD).length();
    }

    /** Завершает подборку приглашением и ссылкой на полный каталог в формате платформы. */
    private String catalogueInvitation(Platform platform) {
        String catalogueUrl = siteUrl + "/games";
        String catalogueLink = switch (platform) {
            case TELEGRAM -> "<a href=\"" + HtmlUtils.htmlEscapeDecimal(catalogueUrl) + "\">полный список игр на сайте</a>";
            case VK -> "полный список игр на сайте (" + catalogueUrl + ")";
            case DISCORD -> "[полный список игр на сайте](" + catalogueUrl + ")";
        };
        return "Хотите больше вариантов? Загляните в " + catalogueLink
                + " — там вас ждут другие приключения и новые знакомства. Будем рады каждому!";
    }

    /** Отключает карточки ссылок и упоминания для каждого сообщения. */
    private static DigestMessage message(String content, int gameCount) {
        return new DigestMessage(Map.of("content", content, "flags", SUPPRESS_EMBEDS,
                "allowed_mentions", Map.of("parse", List.of())), gameCount);
    }

    /** Показывает название, систему, места, жанры и ссылку без описания игры. */
    private static String gameBlock(GameEntry game) {
        return gameDetails(game) + "\n[Подробнее](" + game.url() + ")";
    }

    /** Даёт всем платформам одинаковые сведения без описаний и разметки ссылок. */
    private static String gameDetails(GameEntry game) {
        return game.title() + "\n" + game.system() + " · Занято " + game.takenSeats() + "/" + game.maxPlayers()
                + " · Свободно " + (game.maxPlayers() - game.takenSeats())
                + (game.genreSummary().isBlank() ? "" : "\nЖанры: " + game.genreSummary());
    }

    /** Разрывает упоминания VK вида @адрес невидимым символом; скобки и звёздочки упоминаний уже убраны. */
    private static String withoutMentions(String text) {
        return text.replace("@", "@\u2060");
    }

    /** Убирает разметку и управляющие символы из пользовательских названий. */
    static String compact(String text, int maximum) {
        String cleaned = DigestText.compact(text, maximum);
        return cleaned.isEmpty() ? "Игра" : cleaned;
    }
}
