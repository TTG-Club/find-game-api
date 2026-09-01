package club.ttg.findgame.nexus;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.nexus.api.CreateNexusRequest;
import club.ttg.findgame.nexus.api.NexusMemberResponse;
import club.ttg.findgame.nexus.api.NexusResponse;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NexusServiceTest {

    @Mock
    private NexusRepository nexusRepository;

    @Mock
    private NexusMemberRepository memberRepository;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameRegistrationRepository registrationRepository;

    @Test
    void ownerGetsInviteCodeOfOwnRoom() {
        UUID ownerId = UUID.randomUUID();
        when(nexusRepository.save(any(Nexus.class)))
                .thenAnswer(invocation -> saved(invocation.getArgument(0)));

        NexusResponse response = service().create(
                ownerId, new CreateNexusRequest("  Стол по вторникам  "));

        // Название чистится по краям: в списке комнат отступы не нужны.
        assertThat(response.title()).isEqualTo("Стол по вторникам");
        assertThat(response.owner()).isTrue();
        assertThat(response.inviteCode()).isNotNull();
        assertThat(response.gameId()).isNull();
        // Пока никого не позвали, в комнате один владелец.
        assertThat(response.memberCount()).isEqualTo(1);
    }

    @Test
    void guestDoesNotSeeInviteCode() {
        UUID ownerId = UUID.randomUUID();
        UUID guestId = UUID.randomUUID();
        Nexus nexus = standaloneNexus(ownerId);
        when(nexusRepository.findById(nexus.getId())).thenReturn(Optional.of(nexus));
        when(memberRepository.existsByNexusIdAndUserId(nexus.getId(), guestId))
                .thenReturn(true);
        when(memberRepository.findAllByNexusIdOrderByJoinedAtAsc(nexus.getId()))
                .thenReturn(List.of(NexusMember.of(nexus.getId(), guestId)));

        NexusResponse response = service().get(guestId, nexus.getId());

        // Код приглашения — право звать в комнату, и оно есть только у владельца.
        assertThat(response.inviteCode()).isNull();
        assertThat(response.owner()).isFalse();
    }

    @Test
    void strangerDoesNotSeeSomeoneElsesRoom() {
        UUID ownerId = UUID.randomUUID();
        Nexus nexus = standaloneNexus(ownerId);
        when(nexusRepository.findById(nexus.getId())).thenReturn(Optional.of(nexus));
        when(memberRepository.existsByNexusIdAndUserId(any(), any())).thenReturn(false);

        // Чужая комната не отличается от несуществующей: иначе по ответу можно
        // перебирать чужие идентификаторы.
        assertThatThrownBy(() -> service().get(UUID.randomUUID(), nexus.getId()))
                .isInstanceOf(NexusNotFoundException.class);
    }

    @Test
    void linkPutsVisitorIntoTheRoom() {
        UUID ownerId = UUID.randomUUID();
        UUID visitorId = UUID.randomUUID();
        Nexus nexus = standaloneNexus(ownerId);
        when(nexusRepository.findByInviteCode(nexus.getInviteCode()))
                .thenReturn(Optional.of(nexus));
        when(memberRepository.existsByNexusIdAndUserId(nexus.getId(), visitorId))
                .thenReturn(false);
        when(memberRepository.findAllByNexusIdOrderByJoinedAtAsc(nexus.getId()))
                .thenReturn(List.of());

        service().joinByInvite(visitorId, nexus.getInviteCode());

        verify(memberRepository).saveAndFlush(any(NexusMember.class));
    }

    @Test
    void secondVisitByTheSameLinkChangesNothing() {
        UUID visitorId = UUID.randomUUID();
        Nexus nexus = standaloneNexus(UUID.randomUUID());
        when(nexusRepository.findByInviteCode(nexus.getInviteCode()))
                .thenReturn(Optional.of(nexus));
        when(memberRepository.existsByNexusIdAndUserId(nexus.getId(), visitorId))
                .thenReturn(true);
        when(memberRepository.findAllByNexusIdOrderByJoinedAtAsc(nexus.getId()))
                .thenReturn(List.of(NexusMember.of(nexus.getId(), visitorId)));

        service().joinByInvite(visitorId, nexus.getInviteCode());

        verify(memberRepository, never()).saveAndFlush(any(NexusMember.class));
    }

    @Test
    void gameRoomAppearsOnFirstVisit() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = game(masterId, gameId);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(nexusRepository.findByGameId(gameId)).thenReturn(Optional.empty());
        when(nexusRepository.saveAndFlush(any(Nexus.class)))
                .thenAnswer(invocation -> saved(invocation.getArgument(0)));
        when(registrationRepository.findAllByGameIdOrderByCreatedAtAsc(gameId))
                .thenReturn(List.of());

        NexusResponse response = service().getForGame(masterId, gameId);

        // Комната заводится при первом входе: у большинства игр она так и не
        // понадобится.
        assertThat(response.gameId()).isEqualTo(gameId);
        assertThat(response.owner()).isTrue();
        // Ссылкой комната игры не зовёт — туда попадают со страницы игры.
        assertThat(response.inviteCode()).isNull();
    }

    @Test
    void gameRoomOpensToApplicantsAndClosesToOthers() {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        Game game = game(UUID.randomUUID(), gameId);
        Nexus nexus = gameNexus(game);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                gameId, playerId, RegistrationStatus.REJECTED)).thenReturn(true);
        when(registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                gameId, strangerId, RegistrationStatus.REJECTED)).thenReturn(false);
        when(nexusRepository.findByGameId(gameId)).thenReturn(Optional.of(nexus));
        List<GameRegistration> registrations =
                List.of(registration(playerId, RegistrationStatus.PENDING));
        when(registrationRepository.findAllByGameIdOrderByCreatedAtAsc(gameId))
                .thenReturn(registrations);

        NexusService service = service();

        assertThat(service.getForGame(playerId, gameId).gameId()).isEqualTo(gameId);

        assertThatThrownBy(() -> service.getForGame(strangerId, gameId))
                .isInstanceOf(NexusAccessDeniedException.class);
    }

    @Test
    void gameRoomLineupComesFromApplications() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID approvedId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        Nexus nexus = gameNexus(game(masterId, gameId));
        when(nexusRepository.findById(nexus.getId())).thenReturn(Optional.of(nexus));
        List<GameRegistration> registrations = List.of(
                registration(approvedId, RegistrationStatus.APPROVED),
                registration(pendingId, RegistrationStatus.PENDING),
                registration(UUID.randomUUID(), RegistrationStatus.REJECTED));
        when(registrationRepository.findAllByGameIdOrderByCreatedAtAsc(gameId))
                .thenReturn(registrations);

        List<NexusMemberResponse> members = service().findMembers(masterId, nexus.getId());

        // Отклонённая заявка в состав не входит: игра, куда не взяли, комнаты
        // не открывает.
        assertThat(members).extracting(NexusMemberResponse::userId)
                .containsExactly(masterId, approvedId, pendingId);
        assertThat(members.getFirst().owner()).isTrue();
    }

    @Test
    void gameRoomLineupIsNotEditedByHand() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Nexus nexus = gameNexus(game(masterId, gameId));
        when(nexusRepository.findById(nexus.getId())).thenReturn(Optional.of(nexus));

        assertThatThrownBy(() -> service()
                .removeMember(masterId, nexus.getId(), UUID.randomUUID()))
                .isInstanceOf(InvalidNexusException.class);
    }

    @Test
    void memberLeavesStandaloneRoomOnTheirOwn() {
        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Nexus nexus = standaloneNexus(ownerId);
        when(nexusRepository.findById(nexus.getId())).thenReturn(Optional.of(nexus));

        service().removeMember(memberId, nexus.getId(), memberId);

        verify(memberRepository).deleteByNexusIdAndUserId(nexus.getId(), memberId);
    }

    @Test
    void strangerDoesNotEvictSomeoneElse() {
        Nexus nexus = standaloneNexus(UUID.randomUUID());
        when(nexusRepository.findById(nexus.getId())).thenReturn(Optional.of(nexus));

        assertThatThrownBy(() -> service()
                .removeMember(UUID.randomUUID(), nexus.getId(), UUID.randomUUID()))
                .isInstanceOf(NexusAccessDeniedException.class);

        verify(memberRepository, never()).deleteByNexusIdAndUserId(any(), any());
    }

    @Test
    void ownerDoesNotLeaveOwnRoom() {
        UUID ownerId = UUID.randomUUID();
        Nexus nexus = standaloneNexus(ownerId);
        when(nexusRepository.findById(nexus.getId())).thenReturn(Optional.of(nexus));

        assertThatThrownBy(() -> service().removeMember(ownerId, nexus.getId(), ownerId))
                .isInstanceOf(InvalidNexusException.class);
    }

    private NexusService service() {
        return new NexusService(
                nexusRepository, memberRepository, gameRepository, registrationRepository);
    }

    private static Nexus saved(Nexus nexus) {
        nexus.prePersist();

        return nexus;
    }

    private static Nexus standaloneNexus(UUID ownerId) {
        Nexus nexus = new Nexus();

        nexus.setTitle("Стол по вторникам");
        nexus.setOwnerId(ownerId);
        nexus.setInviteCode(UUID.randomUUID());

        return saved(nexus);
    }

    private static Nexus gameNexus(Game game) {
        Nexus nexus = new Nexus();

        nexus.setTitle("Проклятие Страда");
        nexus.setOwnerId(game.getMasterId());
        nexus.setGameId(game.getId());

        return saved(nexus);
    }

    private static Game game(UUID masterId, UUID gameId) {
        Game game = mock(Game.class);

        when(game.getMasterId()).thenReturn(masterId);
        when(game.getId()).thenReturn(gameId);

        return game;
    }

    private static GameRegistration registration(
            UUID playerId,
            RegistrationStatus status
    ) {
        GameRegistration registration = mock(GameRegistration.class);

        // Отклонённую заявку сервис отбрасывает по статусу и до игрока не
        // доходит — стабы мягкие, иначе тест падал бы на лишнем из них.
        lenient().when(registration.getPlayerId()).thenReturn(playerId);
        lenient().when(registration.getStatus()).thenReturn(status);

        return registration;
    }
}
