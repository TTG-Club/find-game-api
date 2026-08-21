package club.ttg.findgame.registration;

import club.ttg.findgame.registration.api.CreateSessionRegistrationRequest;
import club.ttg.findgame.registration.api.SessionRegistrationResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SessionRegistrationMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sessionId", ignore = true)
    @Mapping(target = "playerId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "attendanceStatus", ignore = true)
    @Mapping(target = "paidAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    SessionRegistration toEntity(CreateSessionRegistrationRequest request);

    @Mapping(target = "paid", expression = "java(registration.getPaidAt() != null)")
    SessionRegistrationResponse toResponse(SessionRegistration registration);
}
