--liquibase formatted sql

--changeset codex:019-add-registration-payment-status
ALTER TABLE game_session_registrations
    ADD COLUMN paid_at TIMESTAMPTZ;

--rollback ALTER TABLE game_session_registrations DROP COLUMN paid_at;
