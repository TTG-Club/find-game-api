package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.UpdateGameRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Обложка игры приходит из загрузчика сайта относительным путём
 * {@code /s3/<ключ>} — так их хранят все разделы. Проверка на абсолютный URL
 * такие пути отвергала, и сохранить игру с обложкой было нельзя.
 */
class GameImageUrlValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/s3/games/master/cover.webp",
            "https://cdn.example.org/games/strahd.jpg",
            "http://cdn.example.org/games/strahd.jpg"
    })
    void acceptsSiteStoragePathAndExternalLink(String imageUrl) {
        assertThat(imageViolations(create(imageUrl))).isEmpty();
        assertThat(imageViolations(update(imageUrl))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "games/master/cover.webp",
            "ftp://cdn.example.org/cover.webp",
            "не ссылка"
    })
    void rejectsWhatIsNeitherPathNorLink(String imageUrl) {
        assertThat(imageViolations(create(imageUrl))).isNotEmpty();
        assertThat(imageViolations(update(imageUrl))).isNotEmpty();
    }

    @Test
    void allowsGameWithoutCover() {
        assertThat(imageViolations(create(null))).isEmpty();
        assertThat(imageViolations(update(null))).isEmpty();
    }

    private static <T> Set<ConstraintViolation<T>> imageViolations(T request) {
        return validator.validateProperty(request, "imageUrl");
    }

    private static CreateGameRequest create(String imageUrl) {
        return new CreateGameRequest(
                "Проклятие Страда", GameSystem.DND_2024, imageUrl, null, null,
                "Описание", "Требования", null, GameType.ONLINE, null,
                3, 5, null, null, 1, true,
                GameDurationType.CAMPAIGN, GameCostType.FREE, GameVisibility.PUBLIC);
    }

    private static UpdateGameRequest update(String imageUrl) {
        return new UpdateGameRequest(
                "Проклятие Страда", GameSystem.DND_2024, imageUrl, null, null,
                "Описание", "Требования", null, GameType.ONLINE, null,
                3, 5, null, null, 1, true,
                GameDurationType.CAMPAIGN, GameCostType.FREE, GameVisibility.PUBLIC);
    }
}
