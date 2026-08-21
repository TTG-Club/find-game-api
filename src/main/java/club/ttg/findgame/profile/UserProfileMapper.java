package club.ttg.findgame.profile;

import club.ttg.findgame.profile.api.MasterProfileResponse;
import club.ttg.findgame.profile.api.PlayerProfileResponse;
import club.ttg.findgame.profile.api.UserProfileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserProfileMapper {

    @Mapping(target = "master", source = "masterProfile")
    @Mapping(target = "player", source = "playerProfile")
    UserProfileResponse toResponse(UserProfile profile);

    MasterProfileResponse toResponse(MasterProfile profile);

    PlayerProfileResponse toResponse(PlayerProfile profile);
}
