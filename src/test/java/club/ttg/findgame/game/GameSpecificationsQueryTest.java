package club.ttg.findgame.game;

import club.ttg.findgame.favorite.FavoriteGame;
import club.ttg.findgame.game.api.GameSearchFilter;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.session.GameSession;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Проверяет на настоящем построителе запросов, без рабочей базы, что условия
 * поиска ссылаются на существующие поля: моки в {@link GameSpecificationsTest}
 * опечатку в имени поля или связи не заметят.
 */
class GameSpecificationsQueryTest {

    @Test
    void genreFilterBuildsQuery() {
        Configuration configuration = new Configuration()
                .addAnnotatedClass(Game.class)
                .addAnnotatedClass(Genre.class)
                .addAnnotatedClass(FavoriteGame.class)
                .addAnnotatedClass(GameRegistration.class)
                .addAnnotatedClass(GameSession.class)
                .setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .setProperty("hibernate.boot.allow_jdbc_metadata_access", "false")
                .setProperty("hibernate.hbm2ddl.auto", "none");

        GameSearchFilter filter = new GameSearchFilter(
                null, null, Set.of("Хоррор", Genre.HOMEBREW), Set.of("Вестерн"),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);

        try (var sessionFactory = configuration.buildSessionFactory();
             var session = sessionFactory.openSession()) {
            CriteriaBuilder criteriaBuilder = session.getCriteriaBuilder();
            CriteriaQuery<Game> query = criteriaBuilder.createQuery(Game.class);
            Root<Game> root = query.from(Game.class);

            assertThatCode(() -> {
                query.where(GameSpecifications.publicGames(filter, null)
                        .toPredicate(root, query, criteriaBuilder));
                session.createQuery(query);
            }).doesNotThrowAnyException();
        }
    }
}
