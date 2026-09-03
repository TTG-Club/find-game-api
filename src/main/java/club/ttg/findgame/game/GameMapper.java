package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.GameResponse;
import club.ttg.findgame.game.api.UpdateGameRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface GameMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "masterId", ignore = true)
    @Mapping(target = "inviteCode", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "recruitmentClosed", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "listPositionAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletionReason", ignore = true)
    @Mapping(
            target = "crossplayAllowed",
            expression = "java(Boolean.TRUE.equals(request.crossplayAllowed()))"
    )
    Game toEntity(CreateGameRequest request);

    /**
     * Переносит правки в существующую игру. Владение, статус, код приглашения
     * и отметки времени редактированием не управляются: их ведёт сервис.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "masterId", ignore = true)
    @Mapping(target = "inviteCode", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "recruitmentClosed", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "listPositionAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletionReason", ignore = true)
    @Mapping(
            target = "crossplayAllowed",
            expression = "java(Boolean.TRUE.equals(request.crossplayAllowed()))"
    )
    void updateEntity(@MappingTarget Game game, UpdateGameRequest request);

    /**
     * Число занятых мест в самой игре не хранится: оно выводится из заявок,
     * поэтому приходит отдельным параметром.
     */
    GameResponse toResponse(Game game, int takenSeats, int approvedSeats);
}
