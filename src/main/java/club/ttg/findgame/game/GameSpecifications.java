package club.ttg.findgame.game;

import club.ttg.findgame.game.api.GameSearchFilter;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.RegistrationStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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

            predicates.add(hasFreeSeat(root, query, criteriaBuilder));

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * В поиске остаются только игры со свободным местом.
     *
     * Собранный стол искать незачем: заявку туда всё равно не примут. У себя
     * в «Моих играх» такая игра остаётся — там она нужна и мастеру, и тем,
     * кого уже взяли.
     *
     * Место занимает поданная заявка, а не только принятая: пока мастер
     * думает, оно не свободно.
     */
    private static Predicate hasFreeSeat(
            Root<Game> root,
            CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder
    ) {
        Subquery<Long> taken = query.subquery(Long.class);
        Root<GameRegistration> registration = taken.from(GameRegistration.class);

        taken.select(criteriaBuilder.count(registration))
                .where(criteriaBuilder.and(
                        criteriaBuilder.equal(registration.get("gameId"), root.get("id")),
                        criteriaBuilder.notEqual(
                                registration.get("status"), RegistrationStatus.REJECTED)));

        return criteriaBuilder.lessThan(taken, root.get("maxPlayers").as(Long.class));
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
