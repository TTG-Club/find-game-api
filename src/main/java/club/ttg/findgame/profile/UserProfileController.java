package club.ttg.findgame.profile;

import club.ttg.findgame.profile.api.UpdateUserProfileRequest;
import club.ttg.findgame.profile.api.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profiles")
@Tag(name = "Profiles")
@SecurityRequirement(name = "bearerAuth")
public class UserProfileController {

    private final UserProfileService service;

    public UserProfileController(UserProfileService service) {
        this.service = service;
    }

    @GetMapping("/me")
    @Operation(summary = "Получить свой профиль Мастера и Игрока")
    public UserProfileResponse getOwnProfile(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt
    ) {
        return service.getOrCreate(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/me")
    @Operation(summary = "Обновить свой профиль Мастера и Игрока")
    public UserProfileResponse updateOwnProfile(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateUserProfileRequest request
    ) {
        return service.update(UUID.fromString(jwt.getSubject()), request);
    }
}
