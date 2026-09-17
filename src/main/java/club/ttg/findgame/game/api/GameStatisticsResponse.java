package club.ttg.findgame.game.api;

/** Число опубликованных неудалённых игр и завершённых среди них. */
public record GameStatisticsResponse(long total, long completed) {
}
