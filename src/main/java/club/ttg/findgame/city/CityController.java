package club.ttg.findgame.city;

import club.ttg.findgame.city.api.CityResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Справочник городов: подсказки для поля города.
 *
 * Открыт всем: каталог игр читают и без входа, и фильтр по городу там тот же
 * самый.
 */
@RestController
@RequestMapping("/api/v1/cities")
@Tag(name = "City")
public class CityController {

    private static final int DEFAULT_LIMIT = 20;

    private final CityRepository repository;

    public CityController(CityRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Найти город по началу названия")
    public List<CityResponse> search(
            @RequestParam(required = false) @Size(max = 120) String q,
            @RequestParam(required = false, defaultValue = "20") @Min(1) @Max(50) int limit
    ) {
        String prefix = q == null ? "" : q.strip();
        Limit rows = Limit.of(limit > 0 ? limit : DEFAULT_LIMIT);

        List<City> cities = prefix.isEmpty()
                ? repository.findLargest(rows)
                : repository.findByNamePrefix(prefix, rows);

        return cities.stream()
                .map(city -> new CityResponse(city.getName(), city.getRegion(), city.getCountry()))
                .toList();
    }
}
