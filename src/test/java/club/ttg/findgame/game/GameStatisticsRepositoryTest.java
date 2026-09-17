package club.ttg.findgame.game;

import club.ttg.findgame.favorite.FavoriteGame;
import club.ttg.findgame.game.api.GameStatisticsResponse;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.session.GameSession;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Выполняет агрегатный запрос на отдельной базе в памяти. */
class GameStatisticsRepositoryTest {

    /** Проверяет пустую базу, смешанные игры и обновление после завершения. */
    @Test
    void countsPublishedGamesAndCompletedSubset() {
        Configuration configuration = new Configuration()
                .addAnnotatedClass(Game.class)
                .addAnnotatedClass(Genre.class)
                .addAnnotatedClass(FavoriteGame.class)
                .addAnnotatedClass(GameRegistration.class)
                .addAnnotatedClass(GameSession.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:game-statistics;MODE=PostgreSQL")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.generate_statistics", "true");

        try (var sessionFactory = configuration.buildSessionFactory();
             var session = sessionFactory.openSession()) {
            GameRepository repository = new JpaRepositoryFactory(session).getRepository(GameRepository.class);
            GameStatisticsService service = new GameStatisticsService(repository);
            assertThat(service.getStatistics()).isEqualTo(new GameStatisticsResponse(0, 0));

            var transaction = session.beginTransaction();
            for (GameVisibility visibility : GameVisibility.values()) {
                for (GameCostType costType : GameCostType.values()) {
                    for (GameStatus status : GameStatus.values()) {
                        session.persist(createGame(status, visibility, costType));
                    }
                }
            }
            Game deletedGame = createGame(GameStatus.CLOSED, GameVisibility.PUBLIC, GameCostType.FREE);
            deletedGame.setDeletedAt(Instant.now());
            session.persist(deletedGame);

            Game recruitmentClosedGame = createGame(GameStatus.OPEN, GameVisibility.PRIVATE, GameCostType.PAID);
            recruitmentClosedGame.setRecruitmentClosed(true);
            session.persist(recruitmentClosedGame);
            transaction.commit();
            session.clear();

            sessionFactory.getStatistics().clear();
            assertThat(service.getStatistics()).isEqualTo(new GameStatisticsResponse(13, 4));
            assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(1);
            assertThat(sessionFactory.getStatistics().getEntityLoadCount()).isZero();

            transaction = session.beginTransaction();
            Game gameToComplete = session.find(Game.class, recruitmentClosedGame.getId());
            gameToComplete.setStatus(GameStatus.CLOSED);
            transaction.commit();
            assertThat(service.getStatistics()).isEqualTo(new GameStatisticsResponse(13, 5));
        }
    }

    /** Создаёт обязательные поля игры для проверки комбинаций статуса, видимости и оплаты. */
    private Game createGame(GameStatus status, GameVisibility visibility, GameCostType costType) {
        Game game = new Game();
        game.setMasterId(UUID.randomUUID());
        game.setTitle("Тестовая игра");
        game.setSystem("DND_2024");
        game.setDescription("Описание");
        game.setRequirements("Требования");
        game.setType(GameType.ONLINE);
        game.setPlayersToStart(1);
        game.setMaxPlayers(5);
        game.setStartingLevel(1);
        game.setStatus(status);
        game.setDurationType(GameDurationType.CAMPAIGN);
        game.setCostType(costType);
        game.setVisibility(visibility);
        return game;
    }
}
