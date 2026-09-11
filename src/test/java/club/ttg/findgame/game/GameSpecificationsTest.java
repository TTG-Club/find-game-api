package club.ttg.findgame.game;

import club.ttg.findgame.game.api.GameSearchFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameSpecificationsTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void combinesExcludedTypeWithAgeRange() {
        Root<Game> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path path = mock(Path.class);
        Predicate publicGame = mock(Predicate.class);
        Predicate notDeleted = mock(Predicate.class);
        Predicate textType = mock(Predicate.class);
        Predicate notTextType = mock(Predicate.class);
        Predicate minimumAge = mock(Predicate.class);
        Predicate maximumAge = mock(Predicate.class);
        Predicate notCancelled = mock(Predicate.class);
        Predicate openRecruitment = mock(Predicate.class);
        Predicate freeSeat = mock(Predicate.class);
        Predicate combined = mock(Predicate.class);

        when(root.get(any(String.class))).thenReturn(path);
        when(criteriaBuilder.equal(any(Expression.class), eq(GameVisibility.PUBLIC))).thenReturn(publicGame);
        when(criteriaBuilder.isNull(any(Expression.class))).thenReturn(notDeleted);
        when(criteriaBuilder.isFalse(any(Expression.class))).thenReturn(openRecruitment);
        when(criteriaBuilder.notEqual(any(Expression.class), eq(GameStatus.CANCELLED)))
                .thenReturn(notCancelled);
        when(path.in(Set.of(GameType.TEXT))).thenReturn(textType);
        when(criteriaBuilder.not(textType)).thenReturn(notTextType);
        when(criteriaBuilder.greaterThanOrEqualTo(any(Expression.class), eq(18))).thenReturn(minimumAge);
        when(criteriaBuilder.lessThanOrEqualTo(any(Expression.class), eq(30))).thenReturn(maximumAge);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(combined);
        // Свободное место считает подзапрос по заявкам — он тоже должен
        // попасть в набор условий поиска.
        stubTakenSeats(query, criteriaBuilder, path);
        when(criteriaBuilder.lessThan(any(Expression.class), any(Expression.class)))
                .thenReturn(freeSeat);

        GameSearchFilter filter = new GameSearchFilter(
                null, null, null, Set.of(GameType.TEXT),
                null, null, null, null, null, null,
                null, null, null, 18, 30, null, null, null);
        Specification<Game> specification = GameSpecifications.publicGames(filter, null);

        Predicate result = specification.toPredicate(root, query, criteriaBuilder);

        ArgumentCaptor<Predicate[]> predicates = ArgumentCaptor.forClass(Predicate[].class);
        verify(criteriaBuilder).and(predicates.capture());
        assertThat(result).isSameAs(combined);
        // Закрытый набор и собранный стол в поиск не попадают: заявку туда
        // всё равно не примут.
        // Отменённой игры, закрытого набора и собранного стола в поиске нет:
        // заявку туда всё равно не примут.
        assertThat(predicates.getValue()).containsExactly(
                publicGame, notDeleted, notCancelled, notTextType, minimumAge, maximumAge,
                openRecruitment, freeSeat);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void limitsSeatsLeftToFullTableAndToStart() {
        Root<Game> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path path = mock(Path.class);
        Predicate combined = mock(Predicate.class);
        Expression seatsToFull = mock(Expression.class);
        Expression seatsToStart = mock(Expression.class);
        Predicate freeSeatsAtMost = mock(Predicate.class);
        Predicate seatsToStartAtMost = mock(Predicate.class);

        when(root.get(any(String.class))).thenReturn(path);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(combined);
        stubTakenSeats(query, criteriaBuilder, path);
        // Оба условия сравнивают один и тот же подзапрос и различаются только
        // вычитаемым, поэтому в отборе они заданы разными числами: иначе в
        // наборе условий их не отличить друг от друга.
        when(criteriaBuilder.diff(any(Expression.class), eq(1L))).thenReturn(seatsToFull);
        when(criteriaBuilder.diff(any(Expression.class), eq(2L))).thenReturn(seatsToStart);
        when(criteriaBuilder.greaterThanOrEqualTo(any(Expression.class), eq(seatsToFull)))
                .thenReturn(freeSeatsAtMost);
        when(criteriaBuilder.greaterThanOrEqualTo(any(Expression.class), eq(seatsToStart)))
                .thenReturn(seatsToStartAtMost);

        GameSearchFilter filter = new GameSearchFilter(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 2, null);
        Specification<Game> specification = GameSpecifications.publicGames(filter, null);

        specification.toPredicate(root, query, criteriaBuilder);

        ArgumentCaptor<Predicate[]> predicates = ArgumentCaptor.forClass(Predicate[].class);
        verify(criteriaBuilder).and(predicates.capture());
        assertThat(predicates.getValue()).contains(freeSeatsAtMost, seatsToStartAtMost);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void skipsSeatConditionsWhenNotAsked() {
        Root<Game> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        Path path = mock(Path.class);
        Predicate combined = mock(Predicate.class);

        when(root.get(any(String.class))).thenReturn(path);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(combined);
        stubTakenSeats(query, criteriaBuilder, path);

        GameSpecifications.publicGames(GameSearchFilter.empty(), null)
                .toPredicate(root, query, criteriaBuilder);

        // Незаданный отбор по местам условием стать не должен: пустой фильтр
        // обязан оставлять выдачу такой же, какой она была до его появления.
        verify(criteriaBuilder, never()).diff(any(Expression.class), any(Long.class));
    }

    /**
     * Заглушает подзапрос по занятым местам: он собирается на каждое условие о
     * местах, и без него отбор падает ещё до сравнения.
     *
     * @param query Запрос, из которого растёт подзапрос.
     * @param criteriaBuilder Построитель условий.
     * @param path Путь, который возвращают все обращения к полям.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubTakenSeats(
            CriteriaQuery<?> query,
            CriteriaBuilder criteriaBuilder,
            Path path
    ) {
        Subquery takenSeats = mock(Subquery.class);
        Root registrations = mock(Root.class);
        Expression takenCount = mock(Expression.class);

        when(query.subquery(Long.class)).thenReturn(takenSeats);
        when(takenSeats.from(any(Class.class))).thenReturn(registrations);
        when(registrations.get(any(String.class))).thenReturn(path);
        when(path.as(Long.class)).thenReturn(path);
        when(criteriaBuilder.count(any(Expression.class))).thenReturn(takenCount);
        when(takenSeats.select(any(Expression.class))).thenReturn(takenSeats);
        when(takenSeats.where(any(Predicate.class))).thenReturn(takenSeats);
    }
}
