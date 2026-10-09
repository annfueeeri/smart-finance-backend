-- Existing MySQL installations only: apply once before running the role-enabled application.
-- New installations use db/schema-users.sql instead.
ALTER TABLE app_user
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT chk_app_user_role CHECK (role IN ('ADMIN', 'USER'));
-- Existing accounts remain USER. A trusted database administrator must explicitly
-- promote the initial account to ADMIN; there is no public administrator signup.
