package club.ttg.findgame.profile;

import club.ttg.findgame.profile.api.PlayerPublicProfileResponse;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.session.GameSessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Игрок глазами тех, с кем он сидит за одним столом.
 *
 * Мастеру перед решением по заявке и соседям по составу нужно понять, кого они
 * зовут за стол: что игрок о себе написал и часто ли он вообще играет. Счётчик
 * берётся из самих встреч — отдельно его никто не ведёт.
 */
@Service
public class PlayerProfileService {

    private final UserProfileRepository profileRepository;
    private final SessionRegistrationRepository participationRepository;

    public PlayerProfileService(
            UserProfileRepository profileRepository,
            SessionRegistrationRepository participationRepository
    ) {
        this.profileRepository = profileRepository;
        this.participationRepository = participationRepository;
    }

    /**
     * Профиль игрока со счётчиком сыгранных встреч.
     *
     * Профиля может не быть вовсе: игрок садится за стол, ничего о себе не
     * написав. Пустой рассказ — это не «нет игрока», поэтому счётчик отдаётся
     * и в этом случае.
     *
     * @param userId Игрок.
     */
    @Transactional(readOnly = true)
    public PlayerPublicProfileResponse get(UUID userId) {
        UserProfile profile = profileRepository.findById(userId).orElse(null);

        return new PlayerPublicProfileResponse(
                userId,
                about(profile),
                profile == null ? null : profile.getTabletopExperienceYears(),
                participationRepository.countCompletedByPlayer(
                        userId, GameSessionStatus.COMPLETED));
    }

    /** Рассказ игрока о себе; пусто — он его не писал. */
    private static String about(UserProfile profile) {
        if (profile == null || profile.getPlayerProfile() == null) {
            return null;
        }

        return profile.getPlayerProfile().getAbout();
    }
}
