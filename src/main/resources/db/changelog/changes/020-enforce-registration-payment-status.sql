--liquibase formatted sql

--changeset codex:020-enforce-registration-payment-status
ALTER TABLE game_session_registrations
    ADD CONSTRAINT ck_game_session_registrations_payment CHECK (
        paid_at IS NULL OR status = 'APPROVED'
    );

--rollback ALTER TABLE game_session_registrations DROP CONSTRAINT ck_game_session_registrations_payment;
