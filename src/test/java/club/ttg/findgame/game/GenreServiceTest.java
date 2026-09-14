package club.ttg.findgame.game;

import club.ttg.findgame.game.api.GenreResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenreServiceTest {

    @Mock
    private GenreRepository repository;

    @Test
    void resolvesGenresFromListIgnoringCase() {
        Genre horror = new Genre("Хоррор");
        when(repository.findAllByNormalizedNameIn(Set.of("хоррор"))).thenReturn(List.of(horror));

        assertThat(new GenreService(repository).resolve(Set.of(" хоррор "))).containsExactly(horror);
    }

    @Test
    void rejectsGenreMissingFromList() {
        when(repository.findAllByNormalizedNameIn(Set.of("хоррор", "моя выдумка")))
                .thenReturn(List.of(new Genre("Хоррор")));

        // Жанр не из списка идёт в свой жанр игры, а справочник не пополняется.
        assertThatThrownBy(() -> new GenreService(repository).resolve(Set.of("Хоррор", "Моя выдумка")))
                .isInstanceOf(GenreNotFoundException.class)
                .hasMessageContaining("Моя выдумка");

        verify(repository, never()).save(any(Genre.class));
    }

    @Test
    void returnsWholeListWithoutQuery() {
        when(repository.findAllByOrderByNameAsc())
                .thenReturn(List.of(new Genre("Вестерн"), new Genre("Хоррор")));

        assertThat(new GenreService(repository).search(null, 20))
                .extracting(GenreResponse::name)
                .containsExactly("Вестерн", "Хоррор");
    }
}
