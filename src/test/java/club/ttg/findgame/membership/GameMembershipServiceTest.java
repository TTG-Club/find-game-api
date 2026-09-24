package club.ttg.findgame.membership;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.membership.api.GameMembersResponse;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameMembershipServiceTest {

    private final GameRepository gameRepository = mock(GameRepository.class);
    private final GameRegistrationRepository registrationRepository = mock(GameRegistrationRepository.class);
    private final GameMembershipService service = new GameMembershipService(gameRepository, registrationRepository);

    private final UUID gameId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();

    @Test
    void masterOfGameIsMaster() {
        givenGame();

        assertThat(service.membership(gameId, masterId).role()).isEqualTo(GameRole.MASTER);
    }

    @Test
    void approvedPlayerIsPlayer() {
        givenGame();
        UUID playerId = UUID.randomUUID();
        when(registrationRepository.existsByGameIdAndPlayerIdAndStatus(
                gameId, playerId, RegistrationStatus.APPROVED)).thenReturn(true);

        assertThat(service.membership(gameId, playerId).role()).isEqualTo(GameRole.PLAYER);
    }

    /** Неразобранная или отклонённая заявка доступа к тому, что строится поверх игры, не даёт. */
    @Test
    void userWithoutApprovedRegistrationIsNone() {
        givenGame();

        assertThat(service.membership(gameId, UUID.randomUUID()).role()).isEqualTo(GameRole.NONE);
    }

    @Test
    void deletedOrMissingGameIsNotFound() {
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.membership(gameId, masterId))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void membersListApprovedPlayersInOrderOfRegistration() {
        givenGame();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GameRegistration later = registration(second, "Эльминстер", Instant.parse("2026-09-02T10:00:00Z"));
        GameRegistration earlier = registration(first, "Дриззт", Instant.parse("2026-09-01T10:00:00Z"));
        when(registrationRepository.findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED))
                .thenReturn(List.of(later, earlier));

        GameMembersResponse members = service.members(gameId);

        assertThat(members.masterId()).isEqualTo(masterId);
        assertThat(members.players())
                .extracting(GameMembersResponse.Player::userId)
                .containsExactly(first, second);
        assertThat(members.players().getFirst().characterName()).isEqualTo("Дриззт");
    }

    private void givenGame() {
        Game game = mock(Game.class);
        lenient().when(game.getId()).thenReturn(gameId);
        lenient().when(game.getMasterId()).thenReturn(masterId);
        lenient().when(game.getTitle()).thenReturn("Проклятие Страда");
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
    }

    private static GameRegistration registration(UUID playerId, String characterName, Instant createdAt) {
        GameRegistration registration = mock(GameRegistration.class);
        lenient().when(registration.getPlayerId()).thenReturn(playerId);
        lenient().when(registration.getCharacterName()).thenReturn(characterName);
        lenient().when(registration.getCreatedAt()).thenReturn(createdAt);

        return registration;
    }
}
