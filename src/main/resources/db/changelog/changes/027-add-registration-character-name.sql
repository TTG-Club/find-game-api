--liquibase formatted sql

--changeset codex:027-add-registration-character-name
-- Игрок может назвать персонажа, не прикладывая лист: ссылка есть не у всех,
-- а мастеру важно знать, кем к нему собираются играть.
ALTER TABLE game_session_registrations
    ADD COLUMN character_name VARCHAR(100);

--rollback ALTER TABLE game_session_registrations DROP COLUMN character_name;
