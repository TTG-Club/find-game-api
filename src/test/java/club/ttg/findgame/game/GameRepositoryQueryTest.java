package club.ttg.findgame.game;

import club.ttg.findgame.favorite.FavoriteGame;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.session.GameSession;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет разбор запросов репозитория игр без подключения к рабочей базе. */
class GameRepositoryQueryTest {

    @Test
    void repositoryQueriesCompile() {
        Configuration configuration = new Configuration()
                .addAnnotatedClass(Game.class)
                .addAnnotatedClass(FavoriteGame.class)
                .addAnnotatedClass(GameRegistration.class)
                .addAnnotatedClass(GameSession.class)
                .setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .setProperty("hibernate.boot.allow_jdbc_metadata_access", "false")
                .setProperty("hibernate.hbm2ddl.auto", "none");

        try (var sessionFactory = configuration.buildSessionFactory();
             var session = sessionFactory.openSession()) {
            GameRepository repository = new JpaRepositoryFactory(session)
                    .getRepository(GameRepository.class);
            assertThat(repository).isNotNull();
        }
    }
}
