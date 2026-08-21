--liquibase formatted sql

--changeset codex:014-add-session-attendance
ALTER TABLE game_session_registrations
    ADD COLUMN attendance_status VARCHAR(20);

UPDATE game_session_registrations
SET attendance_status = 'NOT_ATTENDING'
WHERE status = 'APPROVED';

ALTER TABLE game_session_registrations
    ADD CONSTRAINT ck_game_session_registrations_attendance CHECK (
        (status = 'APPROVED' AND attendance_status IN ('ATTENDING', 'NOT_ATTENDING'))
        OR (status IN ('PENDING', 'REJECTED') AND attendance_status IS NULL)
    );

--rollback ALTER TABLE game_session_registrations DROP CONSTRAINT ck_game_session_registrations_attendance, DROP COLUMN attendance_status;
