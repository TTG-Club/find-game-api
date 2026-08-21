package club.ttg.findgame.profile;

import club.ttg.findgame.profile.api.MasterProfileRequest;
import club.ttg.findgame.profile.api.PlayerProfileRequest;
import club.ttg.findgame.profile.api.UpdateUserProfileRequest;
import club.ttg.findgame.profile.api.UserProfileResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Year;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private UserProfileRepository repository;

    private final UserProfileMapper mapper = Mappers.getMapper(UserProfileMapper.class);

    @Test
    void createsBothProfilesOnFirstAccess() {
        UUID userId = UUID.randomUUID();
        when(repository.findById(userId)).thenReturn(Optional.of(UserProfile.create(userId)));

        UserProfileResponse response = new UserProfileService(repository, mapper).getOrCreate(userId);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.master()).isNotNull();
        assertThat(response.player()).isNotNull();
        verify(repository).insertUserProfileIfMissing(org.mockito.ArgumentMatchers.eq(userId), any());
        verify(repository).insertMasterProfileIfMissing(userId);
        verify(repository).insertPlayerProfileIfMissing(userId);
    }

    @Test
    void updatesSharedMasterAndPlayerInformation() {
        UUID userId = UUID.randomUUID();
        UserProfile profile = UserProfile.create(userId);
        when(repository.findById(userId)).thenReturn(Optional.of(profile));
        when(repository.save(profile)).thenReturn(profile);
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(
                1990,
                Gender.MALE,
                7,
                new MasterProfileRequest("  Вожу сюжетные кампании  "),
                new PlayerProfileRequest("  Люблю исследование мира  ")
        );

        UserProfileResponse response = new UserProfileService(repository, mapper).update(userId, request);

        assertThat(response.birthYear()).isEqualTo(1990);
        assertThat(response.gender()).isEqualTo(Gender.MALE);
        assertThat(response.tabletopExperienceYears()).isEqualTo(7);
        assertThat(response.master().about()).isEqualTo("Вожу сюжетные кампании");
        assertThat(response.player().about()).isEqualTo("Люблю исследование мира");
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void rejectsBirthYearInFuture() {
        UUID userId = UUID.randomUUID();
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(
                Year.now().getValue() + 1,
                Gender.NOT_SPECIFIED,
                0,
                new MasterProfileRequest(null),
                new PlayerProfileRequest(null)
        );

        assertThatThrownBy(() -> new UserProfileService(repository, mapper).update(userId, request))
                .isInstanceOf(InvalidUserProfileException.class);
        verify(repository, never()).save(any());
    }
}
