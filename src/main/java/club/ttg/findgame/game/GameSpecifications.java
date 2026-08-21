package club.ttg.findgame.game;

import club.ttg.findgame.game.api.GameSearchFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class GameSpecifications {

    private GameSpecifications() {
    }

    static Specification<Game> publicGames(GameSearchFilter filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("visibility"), GameVisibility.PUBLIC));
            predicates.add(criteriaBuilder.isNull(root.get("deletedAt")));

            addValues(predicates, root, criteriaBuilder, "system", filter.systems(), false);
            addValues(predicates, root, criteriaBuilder, "system", filter.excludedSystems(), true);
            addValues(predicates, root, criteriaBuilder, "type", filter.types(), false);
            addValues(predicates, root, criteriaBuilder, "type", filter.excludedTypes(), true);
            addValues(predicates, root, criteriaBuilder, "durationType", filter.durationTypes(), false);
            addValues(predicates, root, criteriaBuilder, "durationType", filter.excludedDurationTypes(), true);
            addValues(predicates, root, criteriaBuilder, "costType", filter.costTypes(), false);
            addValues(predicates, root, criteriaBuilder, "costType", filter.excludedCostTypes(), true);
            addValues(predicates, root, criteriaBuilder, "status", filter.statuses(), false);
            addValues(predicates, root, criteriaBuilder, "status", filter.excludedStatuses(), true);
            addCities(predicates, root, criteriaBuilder, filter.cities(), false);
            addCities(predicates, root, criteriaBuilder, filter.excludedCities(), true);

            if (filter.crossplayAllowed() != null) {
                predicates.add(criteriaBuilder.equal(root.get("crossplayAllowed"), filter.crossplayAllowed()));
            }
            if (filter.minAge() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("minAge"), filter.minAge()));
            }
            if (filter.maxAge() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("maxAge"), filter.maxAge()));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void addValues(
            List<Predicate> predicates,
            Root<Game> root,
            CriteriaBuilder criteriaBuilder,
            String attribute,
            Set<?> values,
            boolean excluded
    ) {
        if (values.isEmpty()) {
            return;
        }
        Predicate matches = root.get(attribute).in(values);
        predicates.add(excluded ? criteriaBuilder.not(matches) : matches);
    }

    private static void addCities(
            List<Predicate> predicates,
            Root<Game> root,
            CriteriaBuilder criteriaBuilder,
            Set<String> cities,
            boolean excluded
    ) {
        if (cities.isEmpty()) {
            return;
        }
        Path<String> city = root.get("city");
        Predicate matches = criteriaBuilder.lower(city).in(cities);
        predicates.add(excluded
                ? criteriaBuilder.or(criteriaBuilder.isNull(city), criteriaBuilder.not(matches))
                : matches);
    }
}
