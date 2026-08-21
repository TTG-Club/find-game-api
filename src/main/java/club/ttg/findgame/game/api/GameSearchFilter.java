package club.ttg.findgame.game.api;

import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameDurationType;
import club.ttg.findgame.game.GameStatus;
import club.ttg.findgame.game.GameSystem;
import club.ttg.findgame.game.GameType;
import club.ttg.findgame.game.InvalidGameDetailsException;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record GameSearchFilter(
        Set<GameSystem> systems,
        Set<GameSystem> excludedSystems,
        Set<GameType> types,
        Set<GameType> excludedTypes,
        Set<GameDurationType> durationTypes,
        Set<GameDurationType> excludedDurationTypes,
        Set<GameCostType> costTypes,
        Set<GameCostType> excludedCostTypes,
        Set<GameStatus> statuses,
        Set<GameStatus> excludedStatuses,
        Set<String> cities,
        Set<String> excludedCities,
        Boolean crossplayAllowed,
        Integer minAge,
        Integer maxAge
) {

    public static GameSearchFilter empty() {
        return new GameSearchFilter(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public GameSearchFilter {
        systems = immutable(systems);
        excludedSystems = immutable(excludedSystems);
        types = immutable(types);
        excludedTypes = immutable(excludedTypes);
        durationTypes = immutable(durationTypes);
        excludedDurationTypes = immutable(excludedDurationTypes);
        costTypes = immutable(costTypes);
        excludedCostTypes = immutable(excludedCostTypes);
        statuses = immutable(statuses);
        excludedStatuses = immutable(excludedStatuses);
        cities = normalizeCities(cities);
        excludedCities = normalizeCities(excludedCities);

        if (minAge != null && maxAge != null && minAge > maxAge) {
            throw new InvalidGameDetailsException("Минимальный возраст поиска не может превышать максимальный");
        }
    }

    private static <T> Set<T> immutable(Set<T> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static Set<String> normalizeCities(Set<String> cities) {
        if (cities == null) {
            return Set.of();
        }
        return cities.stream()
                .map(String::trim)
                .filter(city -> !city.isEmpty())
                .map(city -> city.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
