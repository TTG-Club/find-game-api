--liquibase formatted sql

--changeset codex:005-add-genre
ALTER TABLE games
    ADD COLUMN genre VARCHAR(100);

--rollback ALTER TABLE games DROP COLUMN genre;
