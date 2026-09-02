package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.AddNexusTrackerRequest;
import club.ttg.findgame.nexus.api.NexusTrackerResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NexusTrackerServiceTest {

    @Mock
    private NexusTrackerRepository trackerRepository;

    @Mock
    private NexusRepository nexusRepository;

    @Mock
    private NexusService nexusService;

    @Test
    void roomOwnerPutsTrackerIntoTheRoom() {
        UUID ownerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        UUID trackerId = UUID.randomUUID();
        allow(ownerId, nexusId, ownerId);
        when(trackerRepository.existsByNexusIdAndTrackerId(nexusId, trackerId))
                .thenReturn(false);
        when(trackerRepository.saveAndFlush(any(NexusTracker.class)))
                .thenAnswer(invocation -> saved(invocation.getArgument(0)));

        NexusTrackerResponse response = service().add(ownerId, nexusId,
                new AddNexusTrackerRequest(trackerId, "  Засада у моста  "));

        assertThat(response.trackerId()).isEqualTo(trackerId);
        assertThat(response.title()).isEqualTo("Засада у моста");
        assertThat(response.canRemove()).isTrue();
    }

    @Test
    void playerDoesNotSetUpFights() {
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(playerId, nexusId, UUID.randomUUID());

        // Бой ставит тот, кто ведёт игру: иначе у группы появилось бы столько
        // боёв, сколько в ней людей.
        assertThatThrownBy(() -> service().add(playerId, nexusId,
                new AddNexusTrackerRequest(UUID.randomUUID(), "Засада")))
                .isInstanceOf(NexusAccessDeniedException.class);

        verify(trackerRepository, never()).saveAndFlush(any());
    }

    @Test
    void sameTrackerIsNotAddedTwice() {
        UUID ownerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        UUID trackerId = UUID.randomUUID();
        allow(ownerId, nexusId, ownerId);
        when(trackerRepository.existsByNexusIdAndTrackerId(nexusId, trackerId))
                .thenReturn(true);

        assertThatThrownBy(() -> service().add(ownerId, nexusId,
                new AddNexusTrackerRequest(trackerId, "Засада")))
                .isInstanceOf(InvalidNexusException.class);
    }

    @Test
    void playerSeesFightsButDoesNotRemoveThem() {
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(playerId, nexusId, UUID.randomUUID());
        when(trackerRepository.findAllByNexusIdOrderByCreatedAtDesc(nexusId))
                .thenReturn(java.util.List.of(tracker(nexusId, UUID.randomUUID())));

        assertThat(service().findAll(playerId, nexusId))
                .singleElement()
                .satisfies(tracker -> assertThat(tracker.canRemove()).isFalse());
    }

    @Test
    void strangerDoesNotSeeRoomTrackers() {
        when(nexusService.hasAccess(any(), any())).thenReturn(false);

        // Чужая комната не отличается от несуществующей.
        assertThatThrownBy(() -> service().findAll(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NexusNotFoundException.class);
    }

    @Test
    void roomOwnerTakesTrackerAway() {
        UUID ownerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(ownerId, nexusId, ownerId);

        NexusTracker tracker = tracker(nexusId, ownerId);
        when(trackerRepository.findByIdAndNexusId(tracker.getId(), nexusId))
                .thenReturn(Optional.of(tracker));

        service().remove(ownerId, nexusId, tracker.getId());

        // Сам трекер остаётся в core-api: комната лишь перестаёт на него
        // ссылаться.
        verify(trackerRepository).delete(tracker);
    }

    private NexusTrackerService service() {
        return new NexusTrackerService(trackerRepository, nexusRepository, nexusService);
    }

    private void allow(UUID userId, UUID nexusId, UUID roomOwnerId) {
        when(nexusService.hasAccess(nexusId, userId)).thenReturn(true);

        Nexus nexus = new Nexus();

        nexus.setTitle("Стол по вторникам");
        nexus.setOwnerId(roomOwnerId);
        nexus.setInviteCode(UUID.randomUUID());
        nexus.prePersist();

        lenient().when(nexusRepository.findById(nexusId)).thenReturn(Optional.of(nexus));
    }

    private static NexusTracker tracker(UUID nexusId, UUID createdBy) {
        NexusTracker tracker = new NexusTracker();

        tracker.setNexusId(nexusId);
        tracker.setTrackerId(UUID.randomUUID());
        tracker.setTitle("Засада у моста");
        tracker.setCreatedBy(createdBy);

        return saved(tracker);
    }

    private static NexusTracker saved(NexusTracker tracker) {
        tracker.prePersist();

        return tracker;
    }
}
