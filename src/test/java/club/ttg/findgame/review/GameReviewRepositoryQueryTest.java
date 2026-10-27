package club.ttg.findgame.review;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.Genre;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistration;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.session.GameSession;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет разбор запросов, по которым собирается право на оценку, без
 * подключения к рабочей базе.
 */
class GameReviewRepositoryQueryTest {

    @Test
    void repositoryQueriesCompile() {
        Configuration configuration = new Configuration()
                .addAnnotatedClass(GameReview.class)
                .addAnnotatedClass(Game.class)
                .addAnnotatedClass(Genre.class)
                .addAnnotatedClass(GameRegistration.class)
                .addAnnotatedClass(SessionRegistration.class)
                .addAnnotatedClass(GameSession.class)
                .setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .setProperty("hibernate.boot.allow_jdbc_metadata_access", "false")
                .setProperty("hibernate.hbm2ddl.auto", "none");

        try (var sessionFactory = configuration.buildSessionFactory();
             var session = sessionFactory.openSession()) {
            JpaRepositoryFactory factory = new JpaRepositoryFactory(session);

            assertThat(factory.getRepository(GameReviewRepository.class)).isNotNull();
            assertThat(factory.getRepository(GameRegistrationRepository.class)).isNotNull();
            assertThat(factory.getRepository(SessionRegistrationRepository.class)).isNotNull();
        }
    }
}
