--liquibase formatted sql

--changeset codex:032-add-registration-rejection-reason
-- Причина отказа по заявке. Необязательна: мастер вправе не объясняться, но
-- когда объясняет, игрок должен это увидеть — иначе отказ выглядит молчанием.
ALTER TABLE game_registrations
    ADD COLUMN rejection_reason VARCHAR(500);

ALTER TABLE game_registrations
    ADD CONSTRAINT ck_game_registrations_rejection_reason CHECK (
        rejection_reason IS NULL OR LENGTH(BTRIM(rejection_reason)) > 0);

--rollback ALTER TABLE game_registrations DROP CONSTRAINT ck_game_registrations_rejection_reason;
--rollback ALTER TABLE game_registrations DROP COLUMN rejection_reason;
