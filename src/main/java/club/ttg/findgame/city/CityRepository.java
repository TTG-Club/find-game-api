package club.ttg.findgame.city;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CityRepository extends JpaRepository<City, UUID> {

    /**
     * Города, чьё название начинается с введённого. Именно начало, а не любое
     * вхождение: «ов» иначе выдало бы половину справочника, и нужный город
     * искать пришлось бы в этой каше.
     *
     * Крупные города идут первыми — «Мос» должен давать Москву, а не Мосальск.
     */
    @Query("""
            select city from City city
            where lower(city.name) like lower(concat(:prefix, '%'))
            order by city.population desc nulls last, city.name asc
            """)
    List<City> findByNamePrefix(@Param("prefix") String prefix, Limit limit);

    /** Крупнейшие города — их предлагают, пока ничего не введено. */
    @Query("""
            select city from City city
            order by city.population desc nulls last, city.name asc
            """)
    List<City> findLargest(Limit limit);
}
