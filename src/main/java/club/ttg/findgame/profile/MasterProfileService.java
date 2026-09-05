package club.ttg.findgame.profile;

import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameStatus;
import club.ttg.findgame.profile.api.MasterPublicProfileResponse;
import club.ttg.findgame.review.SessionReviewService;
import club.ttg.findgame.review.api.ReputationResponse;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.session.GameSessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Мастер глазами того, кто выбирает игру.
 *
 * Игроку перед заявкой нужно понять, с кем он садится за стол: что мастер о
 * себе написал и что у него было раньше. Счётчики берутся из самих игр —
 * отдельно их никто не ведёт, и разойтись с правдой им негде.
 */
@Service
public class MasterProfileService {

    private final UserProfileRepository profileRepository;
    private final GameRepository gameRepository;
    private final GameSessionRepository sessionRepository;
    private final SessionReviewService reviewService;

    public MasterProfileService(
            UserProfileRepository profileRepository,
            GameRepository gameRepository,
            GameSessionRepository sessionRepository,
            SessionReviewService reviewService
    ) {
        this.profileRepository = profileRepository;
        this.gameRepository = gameRepository;
        this.sessionRepository = sessionRepository;
        this.reviewService = reviewService;
    }

    /**
     * Профиль мастера со счётчиками его игр.
     *
     * Профиля может не быть вовсе: мастер водит, ничего о себе не написав.
     * Пустой рассказ — это не «нет мастера», поэтому счётчики отдаются и в
     * этом случае.
     *
     * @param userId Мастер.
     */
    @Transactional(readOnly = true)
    public MasterPublicProfileResponse get(UUID userId) {
        UserProfile profile = profileRepository.findById(userId).orElse(null);
        ReputationResponse reputation = reviewService.getMasterReputation(userId);

        return new MasterPublicProfileResponse(
                userId,
                about(profile),
                profile == null ? null : profile.getTabletopExperienceYears(),
                gameRepository
                        .countByMasterIdAndStatusAndRecruitmentClosedFalseAndDeletedAtIsNull(
                                userId, GameStatus.OPEN),
                gameRepository.countByMasterIdAndStatusAndDeletedAtIsNull(
                        userId, GameStatus.CLOSED),
                gameRepository.countByMasterIdAndStatusAndDeletedAtIsNull(
                        userId, GameStatus.CANCELLED),
                sessionRepository.countCompletedByMaster(
                        userId, GameSessionStatus.COMPLETED),
                reputation.recommended(),
                reputation.total());
    }

    /** Рассказ мастера о себе; пусто — он его не писал. */
    private static String about(UserProfile profile) {
        if (profile == null || profile.getMasterProfile() == null) {
            return null;
        }

        return profile.getMasterProfile().getAbout();
    }
}
