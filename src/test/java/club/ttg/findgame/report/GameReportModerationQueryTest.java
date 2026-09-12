package club.ttg.findgame.report;

import club.ttg.findgame.game.Game;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет разбор единой очереди модерации без подключения к рабочей базе. */
class GameReportModerationQueryTest {

    @Test
    void moderationQueueQueryCompiles() {
        Configuration configuration = new Configuration()
                .addAnnotatedClass(Game.class)
                .addAnnotatedClass(GameReport.class)
                .setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .setProperty("hibernate.boot.allow_jdbc_metadata_access", "false")
                .setProperty("hibernate.hbm2ddl.auto", "none");

        try (var sessionFactory = configuration.buildSessionFactory();
             var session = sessionFactory.openSession()) {
            GameReportRepository repository = new JpaRepositoryFactory(session)
                    .getRepository(GameReportRepository.class);
            assertThat(repository).isNotNull();
        }
    }
}
