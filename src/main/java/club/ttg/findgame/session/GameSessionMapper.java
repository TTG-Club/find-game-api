package club.ttg.findgame.session;

import club.ttg.findgame.session.api.CreateGameSessionRequest;
import club.ttg.findgame.session.api.GameSessionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface GameSessionMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "gameId", ignore = true)
    @Mapping(target = "status", ignore = true)
    // Отметку завершения ставит сам сервис, когда мастер закрывает встречу.
    @Mapping(target = "completedAt", ignore = true)
    GameSession toEntity(CreateGameSessionRequest request);

    @Mapping(target = "registeredPlayerIds", ignore = true)
    GameSessionResponse toResponse(GameSession session);
}
