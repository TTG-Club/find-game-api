package club.ttg.findgame.game;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum GameType {
    ONLINE("Онлайн"),
    TEXT("Текстовая"),
    OFFLINE("Офлайн");

    final String text;
}
