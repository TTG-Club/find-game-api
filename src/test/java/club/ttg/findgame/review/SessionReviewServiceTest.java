package club.ttg.findgame.review;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.review.api.CreateSessionReviewRequest;
import club.ttg.findgame.review.api.ReputationResponse;
import club.ttg.findgame.review.api.SessionReviewResponse;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.session.GameSessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionReviewServiceTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private SessionReviewRepository reviewRepository;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSessionRepository sessionRepository;

    @Mock
    private SessionRegistrationRepository participationRepository;

    @Mock
    private GameRegistrationRepository registrationRepository;

    @Test
    void playerRatesMasterOfCompletedSession() {
        stubGameAndSession(Instant.now().minus(1, ChronoUnit.DAYS));
        when(participationRepository.existsBySessionIdAndPlayerId(SESSION_ID, PLAYER_ID))
                .thenReturn(true);
        stubSave();

        SessionReviewResponse response = service().review(
                PLAYER_ID, GAME_ID, SESSION_ID, request(MASTER_ID, true, "Вёл ровно"));

        assertThat(response.kind()).isEqualTo(ReviewKind.MASTER_REVIEW);
        assertThat(response.recommended()).isTrue();
        assertThat(response.comment()).isEqualTo("Вёл ровно");
    }

    @Test
    void masterRatesPlayerOfCompletedSession() {
        stubGameAndSession(Instant.now().minus(1, ChronoUnit.DAYS));
        when(participationRepository.existsBySessionIdAndPlayerId(SESSION_ID, PLAYER_ID))
                .thenReturn(true);
        stubSave();

        SessionReviewResponse response = service().review(
                MASTER_ID, GAME_ID, SESSION_ID, request(PLAYER_ID, false, null));

        assertThat(response.kind()).isEqualTo(ReviewKind.PLAYER_REVIEW);
        assertThat(response.recommended()).isFalse();
        // Оценку ставят и молча: пустой отзыв — не отзыв.
        assertThat(response.comment()).isNull();
    }

    @Test
    void strangerDoesNotRateSession() {
        stubGameAndSession(Instant.now().minus(1, ChronoUnit.DAYS));
        when(participationRepository.existsBySessionIdAndPlayerId(any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service().review(
                UUID.randomUUID(), GAME_ID, SESSION_ID, request(MASTER_ID, true, null)))
                .isInstanceOf(ReviewNotAllowedException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    void unfinishedSessionIsNotRated() {
        Game game = game();
        GameSession session = session(GameSessionStatus.SCHEDULED, null);

        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(SESSION_ID, GAME_ID))
                .thenReturn(Optional.of(session));

        // Пока встреча не закрыта, оценивать нечего.
        assertThatThrownBy(() -> service().review(
                PLAYER_ID, GAME_ID, SESSION_ID, request(MASTER_ID, true, null)))
                .isInstanceOf(ReviewNotAllowedException.class);
    }

    @Test
    void closedWindowStopsRating() {
        stubGameAndSession(Instant.now().minus(20, ChronoUnit.DAYS));

        // Через полгода оценку ставят по памяти, а несправедливую уже не
        // исправить: окно закрывается через две недели.
        assertThatThrownBy(() -> service().review(
                PLAYER_ID, GAME_ID, SESSION_ID, request(MASTER_ID, true, null)))
                .isInstanceOf(ReviewWindowClosedException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    void secondSideOpensBothReviews() {
        stubGameAndSession(Instant.now().minus(1, ChronoUnit.DAYS));
        when(participationRepository.existsBySessionIdAndPlayerId(SESSION_ID, PLAYER_ID))
                .thenReturn(true);
        stubSave();

        SessionReview counterpart = new SessionReview();

        counterpart.setSessionId(SESSION_ID);
        counterpart.setAuthorId(MASTER_ID);
        counterpart.setTargetId(PLAYER_ID);

        when(reviewRepository.findBySessionIdAndAuthorIdAndTargetId(
                SESSION_ID, PLAYER_ID, MASTER_ID)).thenReturn(Optional.empty());

        when(reviewRepository.findBySessionIdAndAuthorIdAndTargetId(
                SESSION_ID, MASTER_ID, PLAYER_ID)).thenReturn(Optional.of(counterpart));

        service().review(PLAYER_ID, GAME_ID, SESSION_ID, request(MASTER_ID, true, null));

        // Одна открытая при второй скрытой — то же неравенство, ради которого
        // их и прятали.
        ArgumentCaptor<SessionReview> saved = ArgumentCaptor.forClass(SessionReview.class);

        verify(reviewRepository, atLeastOnce()).save(saved.capture());

        assertThat(saved.getAllValues())
                .allSatisfy(review -> assertThat(review.getVisibleAt()).isNotNull());
    }

    @Test
    void playerReviewsAreReadByGameMasterOnly() {
        Game game = game();

        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.of(game));

        // Отзывы об игроках — разговор мастеров, а не публичная страница.
        assertThatThrownBy(() -> service()
                .findPlayerReviews(UUID.randomUUID(), GAME_ID, PLAYER_ID))
                .isInstanceOf(ReviewNotAllowedException.class);
    }

    @Test
    void playerReviewsNeedAnApplication() {
        Game game = game();

        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.of(game));
        when(registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                GAME_ID, PLAYER_ID, RegistrationStatus.REJECTED)).thenReturn(false);

        // Репутацию читают, когда игрок просится в игру, а не про запас.
        assertThatThrownBy(() -> service()
                .getPlayerReputation(MASTER_ID, GAME_ID, PLAYER_ID))
                .isInstanceOf(ReviewNotAllowedException.class);
    }

    @Test
    void reputationCountsOnlyOpenedReviews() {
        when(reviewRepository.countVisibleRecommended(
                eq(MASTER_ID), eq(ReviewKind.MASTER_REVIEW), any(Instant.class)))
                .thenReturn(11L);

        when(reviewRepository.countVisible(
                eq(MASTER_ID), eq(ReviewKind.MASTER_REVIEW), any(Instant.class)))
                .thenReturn(12L);

        ReputationResponse reputation = service().getMasterReputation(MASTER_ID);

        assertThat(reputation.recommended()).isEqualTo(11);
        assertThat(reputation.total()).isEqualTo(12);
    }

    @Test
    void ownReputationComesWithoutTexts() {
        when(reviewRepository.countVisibleRecommended(any(), any(), any())).thenReturn(3L);
        when(reviewRepository.countVisible(any(), any(), any())).thenReturn(4L);

        ReputationResponse reputation = service().getOwnPlayerReputation(PLAYER_ID);

        assertThat(reputation.userId()).isEqualTo(PLAYER_ID);
        assertThat(reputation.total()).isEqualTo(4);
    }

    @Test
    void masterReviewsAreVisibleToEveryone() {
        SessionReview review = new SessionReview();

        review.setSessionId(SESSION_ID);
        review.setAuthorId(PLAYER_ID);
        review.setTargetId(MASTER_ID);
        review.setKind(ReviewKind.MASTER_REVIEW);
        review.setRecommended(true);

        when(reviewRepository.findVisible(
                eq(MASTER_ID), eq(ReviewKind.MASTER_REVIEW), any(Instant.class)))
                .thenReturn(List.of(review));

        assertThat(service().findMasterReviews(MASTER_ID)).singleElement()
                .satisfies(item -> assertThat(item.recommended()).isTrue());
    }

    private SessionReviewService service() {
        return new SessionReviewService(
                reviewRepository,
                gameRepository,
                sessionRepository,
                participationRepository,
                registrationRepository);
    }

    private void stubGameAndSession(Instant completedAt) {
        Game game = game();
        GameSession session = session(GameSessionStatus.COMPLETED, completedAt);

        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(SESSION_ID, GAME_ID))
                .thenReturn(Optional.of(session));
    }

    /** Встреча игры в заданном состоянии. */
    private static GameSession session(GameSessionStatus status, Instant completedAt) {
        GameSession session = mock(GameSession.class);

        lenient().when(session.getId()).thenReturn(SESSION_ID);
        lenient().when(session.getGameId()).thenReturn(GAME_ID);
        lenient().when(session.getStatus()).thenReturn(status);
        lenient().when(session.getCompletedAt()).thenReturn(completedAt);

        return session;
    }

    private void stubSave() {
        lenient().when(reviewRepository.findBySessionIdAndAuthorIdAndTargetId(any(), any(), any()))
                .thenReturn(Optional.empty());

        lenient().when(reviewRepository.save(any(SessionReview.class)))
                .thenAnswer(invocation -> {
                    SessionReview review = invocation.getArgument(0);

                    review.prePersist();

                    return review;
                });
    }

    /** Игра оценённой встречи. */
    private static Game game() {
        Game game = mock(Game.class);

        lenient().when(game.getId()).thenReturn(GAME_ID);
        lenient().when(game.getMasterId()).thenReturn(MASTER_ID);

        return game;
    }

    private static CreateSessionReviewRequest request(
            UUID targetId,
            boolean recommended,
            String comment
    ) {
        return new CreateSessionReviewRequest(targetId, recommended, comment);
    }
}
