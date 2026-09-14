package club.ttg.findgame.profile;

import club.ttg.findgame.profile.api.PlayerPublicProfileResponse;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.session.GameSessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerProfileServiceTest {

    @Mock
    private UserProfileRepository profileRepository;

    @Mock
    private SessionRegistrationRepository participationRepository;

    @Test
    void playersProfileCarriesSessionCounter() {
        UUID playerId = UUID.randomUUID();
        UserProfile profile = new UserProfile();
        PlayerProfile player = new PlayerProfile();

        player.setAbout("Люблю исследование");
        profile.setPlayerProfile(player);
        profile.setTabletopExperienceYears(7);

        when(profileRepository.findById(playerId)).thenReturn(Optional.of(profile));

        when(participationRepository.countCompletedByPlayer(
                playerId, GameSessionStatus.COMPLETED))
                .thenReturn(12L);

        PlayerPublicProfileResponse response =
                new PlayerProfileService(profileRepository, participationRepository).get(playerId);

        assertThat(response.userId()).isEqualTo(playerId);
        assertThat(response.about()).isEqualTo("Люблю исследование");
        assertThat(response.tabletopExperienceYears()).isEqualTo(7);
        assertThat(response.playedSessions()).isEqualTo(12);
    }

    @Test
    void playerWithoutProfileStillCarriesCounter() {
        UUID playerId = UUID.randomUUID();

        // Игрок садится за стол, ничего о себе не написав: пустой рассказ —
        // это не «нет игрока», и счётчик встреч остаётся честным.
        when(profileRepository.findById(playerId)).thenReturn(Optional.empty());

        when(participationRepository.countCompletedByPlayer(
                playerId, GameSessionStatus.COMPLETED))
                .thenReturn(3L);

        PlayerPublicProfileResponse response =
                new PlayerProfileService(profileRepository, participationRepository).get(playerId);

        assertThat(response.about()).isNull();
        assertThat(response.tabletopExperienceYears()).isNull();
        assertThat(response.playedSessions()).isEqualTo(3);
    }
}
