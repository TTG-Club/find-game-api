--liquibase formatted sql

--changeset codex:037-add-nexus-fight-state
-- Снимок идущего боя. Сам трекер живёт в core-api и открыт только тому, кто
-- ведёт игру, поэтому очередь ходов группа увидеть не может. Клиент мастера
-- складывает сюда то, что за столом и так лежит на виду: порядок хода,
-- текущего бойца и номер раунда — а комната показывает по этому снимку ту же
-- карусель, что и трекер.
ALTER TABLE nexus_trackers
    ADD COLUMN state            JSONB,
    ADD COLUMN state_updated_at TIMESTAMPTZ;

-- Комната показывает бой, который шевелился последним.
CREATE INDEX idx_nexus_trackers_state
    ON nexus_trackers (nexus_id, state_updated_at DESC)
    WHERE state IS NOT NULL;

--rollback DROP INDEX idx_nexus_trackers_state;
--rollback ALTER TABLE nexus_trackers DROP COLUMN state, DROP COLUMN state_updated_at;
