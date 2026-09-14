package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameSystemRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameSystemServiceTest {

    @Mock
    private GameSystemRepository repository;

    @Test
    void returnsSystemsFromDatabase() {
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(
                new GameSystem("DND_2024", "Dungeons & Dragons 2024"),
                new GameSystem("PATHFINDER_2E", "Pathfinder 2e")));

        assertThat(new GameSystemService(repository).findAll())
                .extracting("code", "name")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "DND_2024", "Dungeons & Dragons 2024"),
                        org.assertj.core.groups.Tuple.tuple(
                                "PATHFINDER_2E", "Pathfinder 2e"));
    }

    @Test
    void putsOwnSystemLast() {
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(
                new GameSystem("DND_2024", "D&D 5 (2024)"),
                new GameSystem(GameSystem.HOMEBREW, "Своя система"),
                new GameSystem("FATE_CORE", "Fate Core")));

        assertThat(new GameSystemService(repository).findAll())
                .extracting("code")
                .containsExactly("DND_2024", "FATE_CORE", GameSystem.HOMEBREW);
    }

    @Test
    void createsSystemWithoutApplicationCodeChange() {
        when(repository.save(any(GameSystem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = new GameSystemService(repository).create(
                new CreateGameSystemRequest("PATHFINDER_2E", "  Pathfinder 2e  "));

        assertThat(response.code()).isEqualTo("PATHFINDER_2E");
        assertThat(response.name()).isEqualTo("Pathfinder 2e");
        verify(repository).save(any(GameSystem.class));
    }

    @Test
    void rejectsDuplicateSystemCode() {
        when(repository.existsById("PATHFINDER_2E")).thenReturn(true);

        assertThatThrownBy(() -> new GameSystemService(repository).create(
                new CreateGameSystemRequest("PATHFINDER_2E", "Pathfinder 2e")))
                .isInstanceOf(GameSystemAlreadyExistsException.class)
                .hasMessageContaining("PATHFINDER_2E");

        verify(repository, never()).save(any(GameSystem.class));
    }
}
