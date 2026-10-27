package club.ttg.findgame.review;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameStatus;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.review.api.CreateGameReviewRequest;
import club.ttg.findgame.review.api.GameReviewResponse;
import club.ttg.findgame.review.api.PendingReviewResponse;
import club.ttg.findgame.review.api.ReputationResponse;
import club.ttg.findgame.review.api.ReviewTargetResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameReviewServiceTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();

    @Mock
    private GameReviewRepository reviewRepository;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private SessionRegistrationRepository participationRepository;

    @Mock
    private GameRegistrationRepository registrationRepository;

    @Test
    void playerRatesMasterWithoutAnySessions() {
        stubGame();
        stubApprovedLongAgo(PLAYER_ID);
        stubSave();

        // Мастер не завёл ни одной встречи, но игрок давно в составе — он
        // уже играл и вправе оценить.
        GameReviewResponse response = service().review(
                PLAYER_ID, GAME_ID, request(MASTER_ID, true, "Вёл ровно"));

        assertThat(response.kind()).isEqualTo(ReviewKind.MASTER_REVIEW);
        assertThat(response.recommended()).isTrue();
        assertThat(response.comment()).isEqualTo("Вёл ровно");
    }

    @Test
    void playerFromPastSessionRatesMaster() {
        stubGame();
        when(participationRepository.findPlayedPlayerIds(
                eq(GAME_ID), any(), any(), anyCollection(), any(Instant.class)))
                .thenReturn(List.of(PLAYER_ID));
        stubSave();

        // Принят только что, но уже побывал на встрече: этого достаточно.
        GameReviewResponse response = service().review(
                PLAYER_ID, GAME_ID, request(MASTER_ID, false, null));

        assertThat(response.recommended()).isFalse();
    }

    @Test
    void masterRatesPlayerWhoPlayed() {
        stubGame();
        stubApprovedLongAgo(PLAYER_ID);
        stubSave();

        GameReviewResponse response = service().review(
                MASTER_ID, GAME_ID, request(PLAYER_ID, false, "  "));

        assertThat(response.kind()).isEqualTo(ReviewKind.PLAYER_REVIEW);
        // Оценку ставят и молча: пустой отзыв — не отзыв.
        assertThat(response.comment()).isNull();
    }

    @Test
    void freshMemberDoesNotRateYet() {
        stubGame();

        // Принят сегодня и ни разу не играл: «принял друга — получил лайк» в
        // тот же вечер не проходит.
        assertThatThrownBy(() -> service().review(
                PLAYER_ID, GAME_ID, request(MASTER_ID, true, null)))
                .isInstanceOf(ReviewNotAllowedException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    void playersRateOnlyTheMaster() {
        stubGame();

        assertThatThrownBy(() -> service().review(
                PLAYER_ID, GAME_ID, request(UUID.randomUUID(), true, null)))
                .isInstanceOf(ReviewNotAllowedException.class);
    }

    @Test
    void selfReviewIsRejected() {
        stubGame();

        assertThatThrownBy(() -> service().review(
                MASTER_ID, GAME_ID, request(MASTER_ID, true, null)))
                .isInstanceOf(ReviewNotAllowedException.class);
    }

    @Test
    void revealedReviewIsLocked() {
        stubGame();
        stubApprovedLongAgo(PLAYER_ID);

        GameReview revealed = review(PLAYER_ID, MASTER_ID, ReviewKind.MASTER_REVIEW, true);
        revealed.prePersist();
        revealed.setVisibleAt(Instant.now());

        when(reviewRepository.findByGameIdAndAuthorIdAndTargetId(GAME_ID, PLAYER_ID, MASTER_ID))
                .thenReturn(Optional.of(revealed));

        // Раскрытую оценку уже прочли: поменять её — ответить на чужой вердикт.
        assertThatThrownBy(() -> service().review(
                PLAYER_ID, GAME_ID, request(MASTER_ID, false, null)))
                .isInstanceOf(ReviewLockedException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    void secondSideOpensBothReviews() {
        stubGame();
        stubApprovedLongAgo(PLAYER_ID);
        stubSave();

        GameReview counterpart = review(MASTER_ID, PLAYER_ID, ReviewKind.PLAYER_REVIEW, true);

        when(reviewRepository.findByGameIdAndAuthorIdAndTargetId(GAME_ID, PLAYER_ID, MASTER_ID))
                .thenReturn(Optional.empty());
        when(reviewRepository.findByGameIdAndAuthorIdAndTargetId(GAME_ID, MASTER_ID, PLAYER_ID))
                .thenReturn(Optional.of(counterpart));

        service().review(PLAYER_ID, GAME_ID, request(MASTER_ID, true, null));

        // Одна открытая при второй скрытой — то же неравенство, ради которого
        // их и прятали.
        ArgumentCaptor<GameReview> saved = ArgumentCaptor.forClass(GameReview.class);

        verify(reviewRepository, atLeastOnce()).save(saved.capture());

        assertThat(saved.getAllValues())
                .allSatisfy(item -> assertThat(item.getVisibleAt()).isNotNull());
    }

    @Test
    void playerSeesMasterAsTarget() {
        stubGame();
        stubApprovedLongAgo(PLAYER_ID);

        List<ReviewTargetResponse> targets = service().findTargets(PLAYER_ID, GAME_ID);

        assertThat(targets).singleElement().satisfies(target -> {
            assertThat(target.targetId()).isEqualTo(MASTER_ID);
            assertThat(target.review()).isNull();
            assertThat(target.editable()).isTrue();
        });
    }

    @Test
    void outsiderHasNoTargets() {
        stubGame();

        assertThat(service().findTargets(UUID.randomUUID(), GAME_ID)).isEmpty();
    }

    @Test
    void pendingListsUnratedMasters() {
        Game game = game();
        when(game.getStatus()).thenReturn(GameStatus.OPEN);
        when(game.getTitle()).thenReturn("Проклятие Страда");
        when(registrationRepository.findGameIdsApprovedBefore(eq(PLAYER_ID), any(Instant.class)))
                .thenReturn(List.of(GAME_ID));
        when(gameRepository.findAllById(any())).thenReturn(List.of(game));

        List<PendingReviewResponse> pending = service().findPending(PLAYER_ID);

        assertThat(pending).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(ReviewKind.MASTER_REVIEW);
            assertThat(item.targetIds()).containsExactly(MASTER_ID);
        });
    }

    @Test
    void pendingSkipsRatedMasters() {
        Game game = game();
        when(registrationRepository.findGameIdsApprovedBefore(eq(PLAYER_ID), any(Instant.class)))
                .thenReturn(List.of(GAME_ID));
        when(reviewRepository.findAllByAuthorIdAndGameIdIn(eq(PLAYER_ID), any()))
                .thenReturn(List.of(review(PLAYER_ID, MASTER_ID, ReviewKind.MASTER_REVIEW, true)));
        when(gameRepository.findAllById(any())).thenReturn(List.of(game));

        assertThat(service().findPending(PLAYER_ID)).isEmpty();
    }

    @Test
    void missingCoversBothSides() {
        Game game = game();
        UUID ratedPlayer = UUID.randomUUID();

        when(registrationRepository.findPlayerIdsApprovedBefore(eq(GAME_ID), any(Instant.class)))
                .thenReturn(List.of(PLAYER_ID, ratedPlayer));
        when(reviewRepository.findAllByGameId(GAME_ID)).thenReturn(List.of(
                review(MASTER_ID, ratedPlayer, ReviewKind.PLAYER_REVIEW, true),
                review(ratedPlayer, MASTER_ID, ReviewKind.MASTER_REVIEW, true)));

        Map<UUID, List<UUID>> missing = service().findMissing(game, Instant.now());

        assertThat(missing).containsOnlyKeys(MASTER_ID, PLAYER_ID);
        assertThat(missing.get(MASTER_ID)).containsExactly(PLAYER_ID);
        assertThat(missing.get(PLAYER_ID)).containsExactly(MASTER_ID);
    }

    @Test
    void playerReviewsAreReadByGameMasterOnly() {
        stubGame();

        // Отзывы об игроках — разговор мастеров, а не публичная страница.
        assertThatThrownBy(() -> service()
                .findPlayerReviews(UUID.randomUUID(), GAME_ID, PLAYER_ID))
                .isInstanceOf(ReviewNotAllowedException.class);
    }

    @Test
    void playerReviewsNeedAnApplication() {
        stubGame();
        when(registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                GAME_ID, PLAYER_ID, RegistrationStatus.REJECTED)).thenReturn(false);

        // Репутацию читают, когда игрок просится в игру, а не про запас.
        assertThatThrownBy(() -> service()
                .getPlayerReputation(MASTER_ID, GAME_ID, PLAYER_ID))
                .isInstanceOf(ReviewNotAllowedException.class);
    }

    @Test
    void reputationCountsPeopleNotGames() {
        UUID friend = UUID.randomUUID();
        UUID critic = UUID.randomUUID();

        // Свежие первыми: у друга пять игр с мастером, но голос у него один.
        when(reviewRepository.findVisible(
                eq(MASTER_ID), eq(ReviewKind.MASTER_REVIEW), any(Instant.class)))
                .thenReturn(List.of(
                        review(friend, MASTER_ID, ReviewKind.MASTER_REVIEW, true),
                        review(critic, MASTER_ID, ReviewKind.MASTER_REVIEW, false),
                        review(friend, MASTER_ID, ReviewKind.MASTER_REVIEW, true),
                        review(friend, MASTER_ID, ReviewKind.MASTER_REVIEW, true),
                        review(critic, MASTER_ID, ReviewKind.MASTER_REVIEW, true)));

        ReputationResponse reputation = service().getMasterReputation(MASTER_ID);

        assertThat(reputation.recommended()).isEqualTo(1);
        assertThat(reputation.total()).isEqualTo(2);
    }

    @Test
    void masterReviewsAreVisibleToEveryone() {
        when(reviewRepository.findVisible(
                eq(MASTER_ID), eq(ReviewKind.MASTER_REVIEW), any(Instant.class)))
                .thenReturn(List.of(review(PLAYER_ID, MASTER_ID, ReviewKind.MASTER_REVIEW, true)));

        assertThat(service().findMasterReviews(MASTER_ID)).singleElement()
                .satisfies(item -> assertThat(item.recommended()).isTrue());
    }

    private GameReviewService service() {
        return new GameReviewService(
                reviewRepository,
                gameRepository,
                participationRepository,
                registrationRepository);
    }

    private void stubGame() {
        Game game = game();

        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.of(game));
    }

    /** Игрок в составе дольше недели. */
    private void stubApprovedLongAgo(UUID playerId) {
        when(registrationRepository.findPlayerIdsApprovedBefore(eq(GAME_ID), any(Instant.class)))
                .thenReturn(List.of(playerId));
    }

    private void stubSave() {
        lenient().when(reviewRepository.findByGameIdAndAuthorIdAndTargetId(any(), any(), any()))
                .thenReturn(Optional.empty());

        lenient().when(reviewRepository.save(any(GameReview.class)))
                .thenAnswer(invocation -> {
                    GameReview review = invocation.getArgument(0);

                    review.prePersist();

                    return review;
                });
    }

    private static GameReview review(
            UUID authorId,
            UUID targetId,
            ReviewKind kind,
            boolean recommended
    ) {
        GameReview review = GameReview.of(GAME_ID, authorId, targetId, kind);

        review.setRecommended(recommended);
        review.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));

        return review;
    }

    private static Game game() {
        Game game = mock(Game.class);

        lenient().when(game.getId()).thenReturn(GAME_ID);
        lenient().when(game.getMasterId()).thenReturn(MASTER_ID);

        return game;
    }

    private static CreateGameReviewRequest request(
            UUID targetId,
            boolean recommended,
            String comment
    ) {
        return new CreateGameReviewRequest(targetId, recommended, comment);
    }
}
