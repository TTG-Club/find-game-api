package club.ttg.findgame.game;

import club.ttg.findgame.favorite.FavoriteGame;
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
import java.util.UUID;

final class GameSpecifications {

    private GameSpecifications() {
    }

    /**
     * Условия публичного поиска.
     *
     * @param filter Условия отбора.
     * @param viewerId Кто смотрит выдачу; {@code null} — гость. Нужен только
     * отбору по избранному: список отметок личный.
     */
    static Specification<Game> publicGames(GameSearchFilter filter, UUID viewerId) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("visibility"), GameVisibility.PUBLIC));
            predicates.add(criteriaBuilder.isNull(root.get("deletedAt")));
            // Отменённая игра не состоялась: искать её незачем, и отбор по
            // статусу вернуть её в выдачу не может. У себя в «Моих играх»
            // мастер её видит — там она нужна.
            predicates.add(criteriaBuilder.notEqual(root.get("status"), GameStatus.CANCELLED));

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

            if (Boolean.TRUE.equals(filter.favorite())) {
                // Гостю отмечать нечем, и подставлять ему чужие отметки не из
                // чего: пустое условие честнее молчаливого показа всей выдачи.
                predicates.add(viewerId == null
                        ? criteriaBuilder.disjunction()
                        : isFavorite(root, query, criteriaBuilder, viewerId));
            }

            // Закрытый набор из поиска уходит: мастер уже собрал группу, и
            // заявку в неё всё равно не примут. В «Моих играх» такая игра
            // остаётся — там она нужна и мастеру, и принятым игрокам.
            predicates.add(criteriaBuilder.isFalse(root.get("recruitmentClosed")));
            predicates.add(hasFreeSeat(root, query, criteriaBuilder));

            if (filter.maxFreeSeats() != null) {
                predicates.add(seatsLeftAtMost(
                        root, query, criteriaBuilder, "maxPlayers", filter.maxFreeSeats()));
            }
            if (filter.maxSeatsToStart() != null) {
                predicates.add(seatsLeftAtMost(
                        root, query, criteriaBuilder, "playersToStart", filter.maxSeatsToStart()));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Подзапрос: отметил ли смотрящий эту игру.
     *
     * Отметка не даёт игре ни видимости, ни места в выдаче сверх общих
     * правил — она только сужает её. Отложенная игра, у которой собрался
     * стол, из поиска всё равно уходит; такую ищут во вкладке избранного,
     * где отбора по свободным местам нет.
     */
    private static Predicate isFavorite(
            Root<Game> root,
            CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder,
            UUID viewerId
    ) {
        Subquery<UUID> favorites = query.subquery(UUID.class);
        Root<FavoriteGame> favorite = favorites.from(FavoriteGame.class);

        favorites.select(favorite.get("gameId"))
                .where(criteriaBuilder.and(
                        criteriaBuilder.equal(favorite.get("gameId"), root.get("id")),
                        criteriaBuilder.equal(favorite.get("ownerId"), viewerId)));

        return criteriaBuilder.exists(favorites);
    }

    /**
     * В поиске остаются только игры со свободным местом.
     *
     * Собранный стол искать незачем: заявку туда всё равно не примут. У себя
     * в «Моих играх» такая игра остаётся — там она нужна и мастеру, и тем,
     * кого уже взяли.
     */
    private static Predicate hasFreeSeat(
            Root<Game> root,
            CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder
    ) {
        return criteriaBuilder.lessThan(
                takenSeats(root, query, criteriaBuilder),
                root.get("maxPlayers").as(Long.class));
    }

    /**
     * Оставляет игры, которым до порога {@code threshold} осталось не больше
     * {@code seats} мест.
     *
     * Порогом служит либо {@code maxPlayers} — тогда считаются свободные
     * места, либо {@code playersToStart} — тогда те, без которых мастер не
     * начнёт. Условие «порог − занято ≤ seats» перевёрнуто в «занято ≥ порог −
     * seats»: так подзапрос по заявкам остаётся одной частью сравнения, а не
     * слагаемым в арифметике над ним.
     *
     * Игру, набравшую больше порога, условие не выбрасывает: у неё осталось
     * отрицательное число мест, а это всё ещё «не больше seats». Для
     * {@code playersToStart} это и нужно — стол сверх минимума собран тем
     * более.
     */
    private static Predicate seatsLeftAtMost(
            Root<Game> root,
            CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder,
            String threshold,
            int seats
    ) {
        return criteriaBuilder.greaterThanOrEqualTo(
                takenSeats(root, query, criteriaBuilder),
                criteriaBuilder.diff(root.get(threshold).as(Long.class), (long) seats));
    }

    /**
     * Подзапрос: сколько мест игры занято.
     *
     * Место занимает поданная заявка, а не только принятая: пока мастер
     * думает, оно не свободно.
     *
     * Каждому условию нужен свой экземпляр подзапроса, поэтому он собирается
     * заново на каждый вызов.
     */
    private static Subquery<Long> takenSeats(
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

        return taken;
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
