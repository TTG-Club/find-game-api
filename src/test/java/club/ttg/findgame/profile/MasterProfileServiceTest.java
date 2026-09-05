package club.ttg.findgame.profile;

import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameStatus;
import club.ttg.findgame.profile.api.MasterPublicProfileResponse;
import club.ttg.findgame.review.SessionReviewService;
import club.ttg.findgame.review.api.ReputationResponse;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.session.GameSessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterProfileServiceTest {

    @Mock
    private UserProfileRepository profileRepository;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSessionRepository sessionRepository;

    @Mock
    private SessionReviewService reviewService;

    @Test
    void mastersProfileCarriesGameCounters() {
        UUID masterId = UUID.randomUUID();
        UserProfile profile = new UserProfile();
        MasterProfile master = new MasterProfile();

        master.setAbout("Вожу с 2015 года");
        profile.setMasterProfile(master);
        profile.setTabletopExperienceYears(10);

        when(profileRepository.findById(masterId)).thenReturn(Optional.of(profile));
        stubCounters(masterId, 2L, 7L, 1L, 34L);

        MasterPublicProfileResponse response = service().get(masterId);

        assertThat(response.about()).isEqualTo("Вожу с 2015 года");
        assertThat(response.tabletopExperienceYears()).isEqualTo(10);
        assertThat(response.recruitingGames()).isEqualTo(2);
        assertThat(response.closedGames()).isEqualTo(7);
        assertThat(response.cancelledGames()).isEqualTo(1);
        assertThat(response.completedSessions()).isEqualTo(34);
    }

    @Test
    void masterWithoutProfileStillHasCounters() {
        UUID masterId = UUID.randomUUID();

        when(profileRepository.findById(masterId)).thenReturn(Optional.empty());
        stubCounters(masterId, 1L, 0L, 0L, 0L);

        MasterPublicProfileResponse response = service().get(masterId);

        // Мастер водит, ничего о себе не написав: это не «нет мастера».
        assertThat(response.about()).isNull();
        assertThat(response.tabletopExperienceYears()).isNull();
        assertThat(response.recruitingGames()).isEqualTo(1);
    }

    private MasterProfileService service() {
        return new MasterProfileService(
                profileRepository, gameRepository, sessionRepository, reviewService);
    }

    private void stubCounters(
            UUID masterId,
            long recruiting,
            long closed,
            long cancelled,
            long sessions
    ) {
        lenient().when(reviewService.getMasterReputation(masterId))
                .thenReturn(new ReputationResponse(masterId, 0, 0));

        lenient().when(gameRepository
                        .countByMasterIdAndStatusAndRecruitmentClosedFalseAndDeletedAtIsNull(
                                masterId, GameStatus.OPEN))
                .thenReturn(recruiting);

        lenient().when(gameRepository.countByMasterIdAndStatusAndDeletedAtIsNull(
                        masterId, GameStatus.CLOSED))
                .thenReturn(closed);

        lenient().when(gameRepository.countByMasterIdAndStatusAndDeletedAtIsNull(
                        masterId, GameStatus.CANCELLED))
                .thenReturn(cancelled);

        lenient().when(sessionRepository.countCompletedByMaster(
                        masterId, GameSessionStatus.COMPLETED))
                .thenReturn(sessions);
    }
}
