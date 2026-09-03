package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.FightStateResponse;

import java.util.UUID;

/**
 * Мастер обновил снимок боя.
 *
 * Раздаёт его комнате чат: живая связь у неё уже есть, и заводить вторую
 * ради карусели незачем.
 *
 * @param nexusId Комната.
 * @param state Снимок, который увидит группа.
 */
public record NexusFightStateSaved(UUID nexusId, FightStateResponse state) {
}
