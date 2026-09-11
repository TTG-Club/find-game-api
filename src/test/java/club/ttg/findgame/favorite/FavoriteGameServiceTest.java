package club.ttg.findgame.favorite;

import club.ttg.findgame.favorite.api.FavoriteGameResponse;
import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FavoriteGameServiceTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();

    @Mock
    private FavoriteGameRepository repository;

    @Mock
    private GameRepository gameRepository;

    @Test
    void playerPutsGameAside() {
        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.of(mock(Game.class)));

        service().addFavorite(OWNER_ID, GAME_ID);

        ArgumentCaptor<FavoriteGame> saved = ArgumentCaptor.forClass(FavoriteGame.class);

        verify(repository).save(saved.capture());

        assertThat(saved.getValue().getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(saved.getValue().getGameId()).isEqualTo(GAME_ID);
    }

    @Test
    void repeatedMarkChangesNothing() {
        when(repository.existsByOwnerIdAndGameId(OWNER_ID, GAME_ID)).thenReturn(true);

        service().addFavorite(OWNER_ID, GAME_ID);

        verify(repository, never()).save(any());
    }

    @Test
    void missingGameIsNotMarked() {
        // Закладка на несуществующую игру потом молча недосчиталась бы
        // карточки: список выглядел бы потерявшим строку.
        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addFavorite(OWNER_ID, GAME_ID))
                .isInstanceOf(GameNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void markIsRemovedWithoutCheckingTheGame() {
        // Звёздочку гасят и на игре, которой уже нет: снятие отсутствующей
        // отметки — не ошибка.
        service().removeFavorite(OWNER_ID, GAME_ID);

        verify(repository).deleteByOwnerIdAndGameId(OWNER_ID, GAME_ID);
    }

    @Test
    void listKeepsOrderFromRepository() {
        Instant markedAt = Instant.parse("2026-01-02T03:04:05Z");
        FavoriteGame favorite = FavoriteGame.of(OWNER_ID, GAME_ID);

        favorite.setCreatedAt(markedAt);

        when(repository.findAllByOwnerIdOrderByCreatedAtDesc(OWNER_ID)).thenReturn(List.of(favorite));

        assertThat(service().findFavorites(OWNER_ID))
                .containsExactly(new FavoriteGameResponse(GAME_ID, markedAt));
    }

    private FavoriteGameService service() {
        return new FavoriteGameService(repository, gameRepository);
    }
}
