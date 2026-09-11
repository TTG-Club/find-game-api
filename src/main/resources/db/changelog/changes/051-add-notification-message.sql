--liquibase formatted sql

--changeset codex:051-add-notification-message
ALTER TABLE find_game_notifications
    ADD COLUMN message VARCHAR(1000);

--rollback ALTER TABLE find_game_notifications DROP COLUMN message;
