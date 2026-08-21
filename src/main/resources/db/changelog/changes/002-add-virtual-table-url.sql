--liquibase formatted sql

--changeset codex:002-add-virtual-table-url
ALTER TABLE games
    ADD COLUMN virtual_table_url VARCHAR(2048);

--rollback ALTER TABLE games DROP COLUMN virtual_table_url;
