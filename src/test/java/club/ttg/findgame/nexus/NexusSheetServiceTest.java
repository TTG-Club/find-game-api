package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.AddNexusSheetRequest;
import club.ttg.findgame.nexus.api.NexusSheetResponse;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NexusSheetServiceTest {

    @Mock
    private NexusCharacterSheetRepository sheetRepository;

    @Mock
    private NexusRepository nexusRepository;

    @Mock
    private NexusService nexusService;

    @Test
    void memberPutsOwnSheetIntoTheRoom() {
        UUID userId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(userId, nexusId, UUID.randomUUID());
        when(sheetRepository.existsByNexusIdAndShareToken(nexusId, "9d1f1d0e"))
                .thenReturn(false);
        when(sheetRepository.saveAndFlush(any(NexusCharacterSheet.class)))
                .thenAnswer(invocation -> saved(invocation.getArgument(0)));

        NexusSheetResponse response = service().add(userId, nexusId,
                new AddNexusSheetRequest("  9d1f1d0e  ", "  Тассельхоф  "));

        // Токен и подпись чистятся по краям: их вставляют из буфера.
        assertThat(response.shareToken()).isEqualTo("9d1f1d0e");
        assertThat(response.characterName()).isEqualTo("Тассельхоф");
        assertThat(response.ownerId()).isEqualTo(userId);
        assertThat(response.canRemove()).isTrue();
    }

    @Test
    void sameSheetIsNotAddedTwice() {
        UUID userId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(userId, nexusId, UUID.randomUUID());
        when(sheetRepository.existsByNexusIdAndShareToken(nexusId, "9d1f1d0e"))
                .thenReturn(true);

        assertThatThrownBy(() -> service().add(userId, nexusId,
                new AddNexusSheetRequest("9d1f1d0e", "Тассельхоф")))
                .isInstanceOf(InvalidNexusException.class);

        verify(sheetRepository, never()).saveAndFlush(any());
    }

    @Test
    void strangerDoesNotSeeRoomSheets() {
        when(nexusService.hasAccess(any(), any())).thenReturn(false);

        // Чужая комната не отличается от несуществующей.
        assertThatThrownBy(() -> service().findAll(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NexusNotFoundException.class);
    }

    @Test
    void ownerTakesAwayAnySheetAndOthersOnlyTheirOwn() {
        UUID roomOwnerId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(roomOwnerId, nexusId, roomOwnerId);
        lenient().when(nexusService.hasAccess(nexusId, playerId)).thenReturn(true);

        NexusCharacterSheet sheet = sheet(nexusId, playerId);
        when(sheetRepository.findByIdAndNexusId(sheet.getId(), nexusId))
                .thenReturn(Optional.of(sheet));

        // Владелец комнаты убирает чужой лист: за столом он ведёт игру.
        service().remove(roomOwnerId, nexusId, sheet.getId());

        verify(sheetRepository).delete(sheet);
    }

    @Test
    void strangerDoesNotTakeAwaySomeoneElsesSheet() {
        UUID nexusId = UUID.randomUUID();
        UUID intruderId = UUID.randomUUID();
        allow(intruderId, nexusId, UUID.randomUUID());

        NexusCharacterSheet sheet = sheet(nexusId, UUID.randomUUID());
        when(sheetRepository.findByIdAndNexusId(sheet.getId(), nexusId))
                .thenReturn(Optional.of(sheet));

        assertThatThrownBy(() -> service().remove(intruderId, nexusId, sheet.getId()))
                .isInstanceOf(NexusAccessDeniedException.class);

        verify(sheetRepository, never()).delete(any());
    }

    @Test
    void playerDoesNotGetTheKeyToSomeoneElsesSheet() {
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(playerId, nexusId, UUID.randomUUID());

        List<NexusCharacterSheet> sheets = List.of(sheet(nexusId, UUID.randomUUID()));
        when(sheetRepository.findAllByNexusIdOrderByCreatedAtAsc(nexusId))
                .thenReturn(sheets);

        // Токен — ключ к листу: с ним лист открывается и в обход комнаты,
        // поэтому чужой игроку не достаётся.
        assertThat(service().findAll(playerId, nexusId))
                .singleElement()
                .satisfies(sheet -> {
                    assertThat(sheet.shareToken()).isNull();
                    assertThat(sheet.canRemove()).isFalse();
                });
    }

    @Test
    void playerKeepsTheKeyToOwnSheet() {
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(playerId, nexusId, UUID.randomUUID());

        List<NexusCharacterSheet> sheets = List.of(sheet(nexusId, playerId));
        when(sheetRepository.findAllByNexusIdOrderByCreatedAtAsc(nexusId))
                .thenReturn(sheets);

        assertThat(service().findAll(playerId, nexusId))
                .singleElement()
                .satisfies(sheet -> assertThat(sheet.shareToken()).isEqualTo("9d1f1d0e"));
    }

    @Test
    void roomOwnerMayTakeAwayEverySheetInTheList() {
        UUID roomOwnerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(roomOwnerId, nexusId, roomOwnerId);

        List<NexusCharacterSheet> sheets = List.of(sheet(nexusId, UUID.randomUUID()));
        when(sheetRepository.findAllByNexusIdOrderByCreatedAtAsc(nexusId))
                .thenReturn(sheets);

        // Владелец комнаты ведёт игру: ему открыт любой лист за столом.
        assertThat(service().findAll(roomOwnerId, nexusId))
                .singleElement()
                .satisfies(sheet -> {
                    assertThat(sheet.canRemove()).isTrue();
                    assertThat(sheet.shareToken()).isEqualTo("9d1f1d0e");
                });
    }

    @Test
    void roomOwnerHandsSheetOverToAPlayer() {
        UUID roomOwnerId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(roomOwnerId, nexusId, roomOwnerId);
        when(nexusService.hasAccess(nexusId, playerId)).thenReturn(true);

        NexusCharacterSheet sheet = sheet(nexusId, roomOwnerId);
        when(sheetRepository.findByIdAndNexusId(sheet.getId(), nexusId))
                .thenReturn(Optional.of(sheet));
        when(sheetRepository.save(any(NexusCharacterSheet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NexusSheetResponse response =
                service().transfer(roomOwnerId, nexusId, sheet.getId(), playerId);

        // Заготовленный лист переходит игроку — дальше он и распоряжается им.
        assertThat(response.ownerId()).isEqualTo(playerId);
    }

    @Test
    void sheetIsNotHandedToSomeoneOutsideTheRoom() {
        UUID roomOwnerId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(roomOwnerId, nexusId, roomOwnerId);
        when(nexusService.hasAccess(nexusId, strangerId)).thenReturn(false);

        // Лист не должен достаться тому, кто его даже не увидит.
        assertThatThrownBy(() -> service()
                .transfer(roomOwnerId, nexusId, UUID.randomUUID(), strangerId))
                .isInstanceOf(InvalidNexusException.class);
    }

    @Test
    void playerDoesNotHandSheetsOver() {
        UUID playerId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(playerId, nexusId, UUID.randomUUID());

        // Листы раздаёт владелец комнаты: иначе игрок отдал бы чужого
        // персонажа кому угодно.
        assertThatThrownBy(() -> service()
                .transfer(playerId, nexusId, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(NexusAccessDeniedException.class);
    }

    private NexusSheetService service() {
        return new NexusSheetService(sheetRepository, nexusRepository, nexusService);
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

    private static NexusCharacterSheet sheet(UUID nexusId, UUID ownerId) {
        NexusCharacterSheet sheet = new NexusCharacterSheet();

        sheet.setNexusId(nexusId);
        sheet.setOwnerId(ownerId);
        sheet.setShareToken("9d1f1d0e");
        sheet.setCharacterName("Тассельхоф");

        return saved(sheet);
    }

    private static NexusCharacterSheet saved(NexusCharacterSheet sheet) {
        sheet.prePersist();

        return sheet;
    }
}
