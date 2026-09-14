package club.ttg.findgame.game;

import club.ttg.findgame.game.api.GenreResponse;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GenreService {

    private final GenreRepository repository;

    public GenreService(GenreRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<GenreResponse> search(String query, int limit) {
        return repository.findByNamePrefix(Genre.normalize(query == null ? "" : query), Limit.of(limit)).stream()
                .map(genre -> new GenreResponse(genre.getName()))
                .toList();
    }

    /** Возвращает существующие жанры и добавляет отсутствующие пользовательские значения. */
    @Transactional
    public Set<Genre> resolve(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }

        Map<String, String> requested = names.stream()
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toMap(
                        Genre::normalize,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new));
        Map<String, Genre> resolved = repository.findAllByNormalizedNameIn(requested.keySet()).stream()
                .collect(Collectors.toMap(Genre::getNormalizedName, Function.identity()));

        requested.forEach((normalizedName, displayName) ->
                resolved.computeIfAbsent(normalizedName, ignored -> repository.save(new Genre(displayName))));

        return requested.keySet().stream()
                .map(resolved::get)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
