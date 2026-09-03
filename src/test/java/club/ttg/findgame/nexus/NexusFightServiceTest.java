package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.FightStateRequest;
import club.ttg.findgame.nexus.api.FightStateRequest.FightParticipantRequest;
import club.ttg.findgame.nexus.api.FightStateResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NexusFightServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private NexusTrackerRepository trackerRepository;

    @Mock
    private NexusService nexusService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void roomOwnerPublishesFightState() {
        UUID ownerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        NexusTracker tracker = tracker(nexusId);
        allow(ownerId, nexusId);
        when(nexusService.isOwner(nexusId, ownerId)).thenReturn(true);
        when(trackerRepository.findByNexusIdAndTrackerId(nexusId, tracker.getTrackerId()))
                .thenReturn(Optional.of(tracker));
        when(trackerRepository.saveAndFlush(tracker)).thenReturn(tracker);

        FightStateResponse response = service().publish(ownerId, nexusId,
                state(tracker.getTrackerId()));

        assertThat(response.round()).isEqualTo(2);
        assertThat(response.active()).isTrue();
        assertThat(response.currentParticipantId()).isEqualTo("p-1");
        assertThat(response.participants()).hasSize(2);
        // Комнате снимок раздаёт чат: живая связь у неё уже есть.
        verify(eventPublisher).publishEvent(any(NexusFightStateSaved.class));
    }

    @Test
    void playerDoesNotPublishFightState() {
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(playerId, nexusId);
        when(nexusService.isOwner(nexusId, playerId)).thenReturn(false);

        // Бой ведёт владелец комнаты — пересказывать чужой ход больше некому.
        assertThatThrownBy(() -> service().publish(playerId, nexusId,
                state(UUID.randomUUID())))
                .isInstanceOf(NexusAccessDeniedException.class);

        verify(trackerRepository, never()).saveAndFlush(any());
    }

    @Test
    void fightOutsideTheRoomIsNotPublished() {
        UUID ownerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        UUID trackerId = UUID.randomUUID();
        allow(ownerId, nexusId);
        when(nexusService.isOwner(nexusId, ownerId)).thenReturn(true);
        when(trackerRepository.findByNexusIdAndTrackerId(nexusId, trackerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().publish(ownerId, nexusId, state(trackerId)))
                .isInstanceOf(NexusNotFoundException.class);
    }

    @Test
    void playerSeesTheFightGoingOn() {
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        NexusTracker tracker = tracker(nexusId);
        allow(playerId, nexusId);
        tracker.setState(objectMapper.writeValueAsString(state(tracker.getTrackerId())));
        tracker.setStateUpdatedAt(Instant.now());
        when(trackerRepository.findFirstByNexusIdAndStateIsNotNullOrderByStateUpdatedAtDesc(nexusId))
                .thenReturn(Optional.of(tracker));

        // Трекер игроку не открыт, но очередь ходов он видит — за тем снимок и
        // заводили.
        assertThat(service().find(playerId, nexusId))
                .hasValueSatisfying(state -> {
                    assertThat(state.title()).isEqualTo("Засада у моста");
                    assertThat(state.participants())
                            .extracting(FightParticipantRequest::name)
                            .containsExactly("Ториан", "Существо");
                });
    }

    @Test
    void brokenSnapshotHidesTheReelInsteadOfFailing() {
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        NexusTracker tracker = tracker(nexusId);
        allow(playerId, nexusId);
        tracker.setState("{");
        tracker.setStateUpdatedAt(Instant.now());
        when(trackerRepository.findFirstByNexusIdAndStateIsNotNullOrderByStateUpdatedAtDesc(nexusId))
                .thenReturn(Optional.of(tracker));

        // Снимок мог писать прежний клиент: карусель просто не показывается.
        assertThat(service().find(playerId, nexusId)).isEmpty();
    }

    @Test
    void strangerDoesNotSeeTheFight() {
        when(nexusService.hasAccess(any(), any())).thenReturn(false);

        // Чужая комната не отличается от несуществующей.
        assertThatThrownBy(() -> service().find(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NexusNotFoundException.class);
    }

    private NexusFightService service() {
        return new NexusFightService(trackerRepository, nexusService, eventPublisher, objectMapper);
    }

    private void allow(UUID userId, UUID nexusId) {
        when(nexusService.hasAccess(nexusId, userId)).thenReturn(true);
    }

    private static FightStateRequest state(UUID trackerId) {
        return new FightStateRequest(trackerId, 2, true, "p-1", List.of(
                new FightParticipantRequest("p-1", "Ториан", true, false, null, "amber"),
                new FightParticipantRequest("c-1", "Существо", false, false, null, null)));
    }

    private static NexusTracker tracker(UUID nexusId) {
        NexusTracker tracker = new NexusTracker();

        tracker.setNexusId(nexusId);
        tracker.setTrackerId(UUID.randomUUID());
        tracker.setTitle("Засада у моста");
        tracker.setCreatedBy(UUID.randomUUID());
        tracker.prePersist();

        return tracker;
    }
}
