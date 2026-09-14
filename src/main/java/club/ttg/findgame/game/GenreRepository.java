package club.ttg.findgame.game;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GenreRepository extends JpaRepository<Genre, UUID> {

    List<Genre> findAllByNormalizedNameIn(Collection<String> normalizedNames);

    @Query("""
            select genre from Genre genre
            where genre.normalizedName like concat(:prefix, '%')
            order by genre.name asc
            """)
    List<Genre> findByNamePrefix(@Param("prefix") String prefix, Limit limit);
}
