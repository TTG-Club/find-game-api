package club.ttg.findgame.game;

import java.util.Collection;

public class GenreNotFoundException extends RuntimeException {

    public GenreNotFoundException(Collection<String> names) {
        super("Жанра нет в списке: " + String.join(", ", names));
    }
}
