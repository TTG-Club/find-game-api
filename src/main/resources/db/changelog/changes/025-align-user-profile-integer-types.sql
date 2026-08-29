--liquibase formatted sql

--changeset codex:025-align-user-profile-integer-types
ALTER TABLE user_profiles
    ALTER COLUMN birth_year TYPE INTEGER USING birth_year::INTEGER,
    ALTER COLUMN tabletop_experience_years TYPE INTEGER USING tabletop_experience_years::INTEGER;

--rollback ALTER TABLE user_profiles ALTER COLUMN birth_year TYPE SMALLINT USING birth_year::SMALLINT;
--rollback ALTER TABLE user_profiles ALTER COLUMN tabletop_experience_years TYPE SMALLINT USING tabletop_experience_years::SMALLINT;
