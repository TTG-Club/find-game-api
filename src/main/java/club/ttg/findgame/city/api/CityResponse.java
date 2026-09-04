package club.ttg.findgame.city.api;

/**
 * Город в подсказке.
 *
 * @param name Название: его и записывают в игру.
 * @param region Область или штат; {@code null} — город известен без уточнения.
 * @param country Страна.
 */
public record CityResponse(String name, String region, String country) {
}
