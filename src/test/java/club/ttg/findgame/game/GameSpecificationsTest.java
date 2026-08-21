package club.ttg.findgame.game;

import club.ttg.findgame.game.api.GameSearchFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        Predicate combined = mock(Predicate.class);

        when(root.get(any(String.class))).thenReturn(path);
        when(criteriaBuilder.equal(any(Expression.class), eq(GameVisibility.PUBLIC))).thenReturn(publicGame);
        when(criteriaBuilder.isNull(any(Expression.class))).thenReturn(notDeleted);
        when(path.in(Set.of(GameType.TEXT))).thenReturn(textType);
        when(criteriaBuilder.not(textType)).thenReturn(notTextType);
        when(criteriaBuilder.greaterThanOrEqualTo(any(Expression.class), eq(18))).thenReturn(minimumAge);
        when(criteriaBuilder.lessThanOrEqualTo(any(Expression.class), eq(30))).thenReturn(maximumAge);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(combined);

        GameSearchFilter filter = new GameSearchFilter(
                null, null, null, Set.of(GameType.TEXT),
                null, null, null, null, null, null,
                null, null, null, 18, 30);
        Specification<Game> specification = GameSpecifications.publicGames(filter);

        Predicate result = specification.toPredicate(root, query, criteriaBuilder);

        ArgumentCaptor<Predicate[]> predicates = ArgumentCaptor.forClass(Predicate[].class);
        verify(criteriaBuilder).and(predicates.capture());
        assertThat(result).isSameAs(combined);
        assertThat(predicates.getValue()).containsExactly(
                publicGame, notDeleted, notTextType, minimumAge, maximumAge);
    }
}
