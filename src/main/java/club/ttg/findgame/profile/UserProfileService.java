package club.ttg.findgame.profile;

import club.ttg.findgame.profile.api.UpdateUserProfileRequest;
import club.ttg.findgame.profile.api.UserProfileResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Year;
import java.util.UUID;

@Service
public class UserProfileService {

    private final UserProfileRepository repository;
    private final UserProfileMapper mapper;

    public UserProfileService(UserProfileRepository repository, UserProfileMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public UserProfileResponse getOrCreate(UUID userId) {
        return mapper.toResponse(findOrCreate(userId));
    }

    @Transactional
    public UserProfileResponse update(UUID userId, UpdateUserProfileRequest request) {
        validateBirthYear(request.birthYear());
        UserProfile profile = findOrCreate(userId);
        profile.setBirthYear(request.birthYear());
        profile.setGender(request.gender());
        profile.setTabletopExperienceYears(request.tabletopExperienceYears());
        profile.getMasterProfile().setAbout(normalize(request.master().about()));
        profile.getPlayerProfile().setAbout(normalize(request.player().about()));
        profile.touch();
        return mapper.toResponse(repository.save(profile));
    }

    private void validateBirthYear(Integer birthYear) {
        if (birthYear != null && birthYear > Year.now().getValue()) {
            throw new InvalidUserProfileException("Год рождения не может быть в будущем");
        }
    }

    private UserProfile findOrCreate(UUID userId) {
        Instant now = Instant.now();
        repository.insertUserProfileIfMissing(userId, now);
        repository.insertMasterProfileIfMissing(userId);
        repository.insertPlayerProfileIfMissing(userId);
        return repository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Не удалось создать профиль пользователя"));
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
