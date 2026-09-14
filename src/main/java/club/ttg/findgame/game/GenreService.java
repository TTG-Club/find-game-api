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

    /**
     * Без запроса отдаёт весь список: он короткий, и форма игры показывает его
     * целиком. С запросом ищет по началу названия.
     */
    @Transactional(readOnly = true)
    public List<GenreResponse> search(String query, int limit) {
        List<Genre> genres = query == null || query.isBlank()
                ? repository.findAllByOrderByNameAsc()
                : repository.findByNamePrefix(Genre.normalize(query), Limit.of(limit));

        return genres.stream()
                .map(genre -> new GenreResponse(genre.getName()))
                .toList();
    }

    /**
     * Находит жанры игры в списке. Жанр не из списка отвергается: свой жанр
     * игра хранит отдельным полем, а справочник не пополняется.
     */
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

        List<String> unknown = requested.entrySet().stream()
                .filter(entry -> !resolved.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (!unknown.isEmpty()) {
            throw new GenreNotFoundException(unknown);
        }

        return requested.keySet().stream()
                .map(resolved::get)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
