package club.ttg.findgame.review;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.review.api.CreateSessionReviewRequest;
import club.ttg.findgame.review.api.ReputationResponse;
import club.ttg.findgame.review.api.SessionReviewResponse;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionNotFoundException;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.session.GameSessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Взаимные оценки за встречу.
 *
 * После закрытой сессии игрок отвечает про мастера, мастер — про каждого
 * игрока. Оценка бинарная: «сыграл бы снова» или нет — на малых числах это
 * честнее пятизвёздочной шкалы, которая быстро схлопывается в сплошные пятёрки.
 *
 * Пока не высказались обе стороны, оценка видна только её автору: увидевший
 * первым отвечал бы тем же, и оценки перестали бы значить хоть что-то.
 */
@Service
public class SessionReviewService {

    /**
     * Сколько времени после встречи её можно оценить.
     *
     * Позже оценку не поставить и не поправить: через полгода её ставят по
     * памяти, а несправедливую уже никто не исправит.
     */
    public static final Duration REVIEW_WINDOW = Duration.ofDays(14);

    private final SessionReviewRepository reviewRepository;
    private final GameRepository gameRepository;
    private final GameSessionRepository sessionRepository;
    private final SessionRegistrationRepository participationRepository;
    private final GameRegistrationRepository registrationRepository;

    public SessionReviewService(
            SessionReviewRepository reviewRepository,
            GameRepository gameRepository,
            GameSessionRepository sessionRepository,
            SessionRegistrationRepository participationRepository,
            GameRegistrationRepository registrationRepository
    ) {
        this.reviewRepository = reviewRepository;
        this.gameRepository = gameRepository;
        this.sessionRepository = sessionRepository;
        this.participationRepository = participationRepository;
        this.registrationRepository = registrationRepository;
    }

    /**
     * Ставит оценку участнику встречи; повторная правит поставленную.
     *
     * @param authorId Кто оценивает.
     * @param gameId Игра встречи.
     * @param sessionId Встреча.
     * @param request Кого и как оценили.
     * @return Поставленная оценка.
     */
    @Transactional
    public SessionReviewResponse review(
            UUID authorId,
            UUID gameId,
            UUID sessionId,
            CreateSessionReviewRequest request
    ) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        GameSession session = sessionRepository.findByIdAndGameId(sessionId, gameId)
                .orElseThrow(() -> new GameSessionNotFoundException(sessionId));

        Instant completedAt = requireCompleted(session);
        UUID targetId = request.targetId();

        if (authorId.equals(targetId)) {
            throw new ReviewNotAllowedException("Себя не оценивают");
        }

        ReviewKind kind = resolveKind(game, session, authorId, targetId);

        SessionReview review = reviewRepository
                .findBySessionIdAndAuthorIdAndTargetId(sessionId, authorId, targetId)
                .orElseGet(SessionReview::new);

        review.setSessionId(sessionId);
        review.setGameId(gameId);
        review.setAuthorId(authorId);
        review.setTargetId(targetId);
        review.setKind(kind);
        review.setRecommended(Boolean.TRUE.equals(request.recommended()));
        review.setComment(normalize(request.comment()));
        review.setSessionCompletedAt(completedAt);

        SessionReview saved = reviewRepository.save(review);

        revealPair(saved);

        return toResponse(saved);
    }

    /**
     * Оценки за встречу глазами пользователя: свои — всегда, чужие о нём — как
     * только пара раскрыта.
     *
     * @param userId Кто смотрит.
     * @param gameId Игра встречи.
     * @param sessionId Встреча.
     */
    @Transactional(readOnly = true)
    public List<SessionReviewResponse> findSessionReviews(
            UUID userId,
            UUID gameId,
            UUID sessionId
    ) {
        GameSession session = sessionRepository.findByIdAndGameId(sessionId, gameId)
                .orElseThrow(() -> new GameSessionNotFoundException(sessionId));

        List<SessionReviewResponse> reviews = new ArrayList<>(
                reviewRepository.findAllBySessionIdAndAuthorId(session.getId(), userId).stream()
                        .map(SessionReviewService::toResponse)
                        .toList());

        // Чужая оценка о смотрящем показывается по тем же правилам, что и в
        // профиле: пока пара не раскрыта, её нет.
        reviewRepository.findVisible(userId, ReviewKind.MASTER_REVIEW, windowEdge()).stream()
                .filter(review -> review.getSessionId().equals(session.getId()))
                .map(SessionReviewService::toResponse)
                .forEach(reviews::add);

        reviewRepository.findVisible(userId, ReviewKind.PLAYER_REVIEW, windowEdge()).stream()
                .filter(review -> review.getSessionId().equals(session.getId()))
                .map(SessionReviewService::toResponse)
                .forEach(reviews::add);

        return reviews;
    }

    /**
     * Репутация мастера: её видно всем, кто смотрит объявление.
     * @param masterId Мастер.
     */
    @Transactional(readOnly = true)
    public ReputationResponse getMasterReputation(UUID masterId) {
        return reputation(masterId, ReviewKind.MASTER_REVIEW);
    }

    /**
     * Отзывы о мастере: их читают в его профиле.
     * @param masterId Мастер.
     */
    @Transactional(readOnly = true)
    public List<SessionReviewResponse> findMasterReviews(UUID masterId) {
        return reviewRepository.findVisible(masterId, ReviewKind.MASTER_REVIEW, windowEdge())
                .stream()
                .map(SessionReviewService::toResponse)
                .toList();
    }

    /**
     * Репутация игрока для мастера, разбирающего его заявку.
     *
     * Читает её только мастер игры, и только пока игрок в неё просится: отзывы
     * об игроках — разговор мастеров между собой, а не публичная страница.
     *
     * @param masterId Мастер из токена.
     * @param gameId Игра, куда подана заявка.
     * @param playerId Игрок.
     */
    @Transactional(readOnly = true)
    public ReputationResponse getPlayerReputation(UUID masterId, UUID gameId, UUID playerId) {
        requireApplicantOfOwnGame(masterId, gameId, playerId);

        return reputation(playerId, ReviewKind.PLAYER_REVIEW);
    }

    /**
     * Отзывы об игроке для мастера, разбирающего его заявку.
     *
     * @param masterId Мастер из токена.
     * @param gameId Игра, куда подана заявка.
     * @param playerId Игрок.
     */
    @Transactional(readOnly = true)
    public List<SessionReviewResponse> findPlayerReviews(
            UUID masterId,
            UUID gameId,
            UUID playerId
    ) {
        requireApplicantOfOwnGame(masterId, gameId, playerId);

        return reviewRepository.findVisible(playerId, ReviewKind.PLAYER_REVIEW, windowEdge())
                .stream()
                .map(SessionReviewService::toResponse)
                .toList();
    }

    /**
     * Своя репутация игрока: доля и число оценок без текстов и авторов.
     *
     * Игрок знает, где стоит, но не идёт выяснять отношения с конкретным
     * мастером — иначе отзывы стали бы осторожными и бесполезными.
     *
     * @param userId Пользователь из токена.
     */
    @Transactional(readOnly = true)
    public ReputationResponse getOwnPlayerReputation(UUID userId) {
        return reputation(userId, ReviewKind.PLAYER_REVIEW);
    }

    /** Кто кого оценивает: мастер — игрока, игрок — мастера. */
    private ReviewKind resolveKind(
            Game game,
            GameSession session,
            UUID authorId,
            UUID targetId
    ) {
        if (game.getMasterId().equals(authorId)) {
            if (!participationRepository.existsBySessionIdAndPlayerId(session.getId(), targetId)) {
                throw new ReviewNotAllowedException("Этот игрок во встрече не участвовал");
            }

            return ReviewKind.PLAYER_REVIEW;
        }

        if (!participationRepository.existsBySessionIdAndPlayerId(session.getId(), authorId)) {
            throw new ReviewNotAllowedException("Оценку ставит участник встречи");
        }

        if (!game.getMasterId().equals(targetId)) {
            throw new ReviewNotAllowedException("Игроки оценивают мастера игры");
        }

        return ReviewKind.MASTER_REVIEW;
    }

    /** Встреча должна быть закрыта, а окно на оценку — ещё открыто. */
    private Instant requireCompleted(GameSession session) {
        if (session.getStatus() != GameSessionStatus.COMPLETED
                || session.getCompletedAt() == null) {
            throw new ReviewNotAllowedException("Оценивают завершённую встречу");
        }

        if (session.getCompletedAt().plus(REVIEW_WINDOW).isBefore(Instant.now())) {
            throw new ReviewWindowClosedException(session.getCompletedAt().plus(REVIEW_WINDOW));
        }

        return session.getCompletedAt();
    }

    /**
     * Раскрывает пару, когда высказалась вторая сторона.
     *
     * Раскрываются обе оценки разом: одна открытая при второй скрытой — то же
     * самое неравенство, ради которого их и прятали.
     */
    private void revealPair(SessionReview review) {
        Optional<SessionReview> counterpart = reviewRepository
                .findBySessionIdAndAuthorIdAndTargetId(
                        review.getSessionId(), review.getTargetId(), review.getAuthorId());

        if (counterpart.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        SessionReview other = counterpart.get();

        if (review.getVisibleAt() == null) {
            review.setVisibleAt(now);
            reviewRepository.save(review);
        }

        if (other.getVisibleAt() == null) {
            other.setVisibleAt(now);
            reviewRepository.save(other);
        }
    }

    /** Мастер разбирает заявку этого игрока в свою игру. */
    private void requireApplicantOfOwnGame(UUID masterId, UUID gameId, UUID playerId) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (!game.getMasterId().equals(masterId)) {
            throw new ReviewNotAllowedException("Отзывы об игроках читает мастер игры");
        }

        boolean applied = registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                gameId, playerId, RegistrationStatus.REJECTED);

        if (!applied) {
            throw new ReviewNotAllowedException("Этот игрок в игру не просится");
        }
    }

    private ReputationResponse reputation(UUID userId, ReviewKind kind) {
        Instant edge = windowEdge();

        return new ReputationResponse(
                userId,
                reviewRepository.countVisibleRecommended(userId, kind, edge),
                reviewRepository.countVisible(userId, kind, edge));
    }

    /** Встречи старше этой отметки раскрыты в любом случае: окно вышло. */
    private static Instant windowEdge() {
        return Instant.now().minus(REVIEW_WINDOW);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.strip();

        return normalized.isEmpty() ? null : normalized;
    }

    private static SessionReviewResponse toResponse(SessionReview review) {
        return new SessionReviewResponse(
                review.getId(),
                review.getSessionId(),
                review.getGameId(),
                review.getAuthorId(),
                review.getTargetId(),
                review.getKind(),
                review.isRecommended(),
                review.getComment(),
                review.getCreatedAt());
    }
}
