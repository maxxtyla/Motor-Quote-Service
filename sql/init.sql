-- =============================================================================
-- CIC Motor Quote Service — Database Initialisation
-- File: sql/init.sql
-- Runs automatically on first `docker compose up` via docker-entrypoint-initdb.d
--
-- INTERN NOTE: JPA ddl-auto: update will also create these tables on first boot.
-- This SQL file exists for two reasons:
--   1. Docker compose first boot — creates the schema before Spring starts
--   2. Seeding the first admin user (JPA can't do that)
--
-- If you already have the tables from JPA, running this manually will fail
-- with "relation already exists" — that's fine, just skip those statements.
-- =============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. app_users table
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS app_users (
                                         id                 BIGSERIAL PRIMARY KEY,
                                         username           VARCHAR(50)  NOT NULL UNIQUE,
    password_hash      TEXT         NOT NULL,           -- BCrypt hash, never plain text
    email              VARCHAR(100) NOT NULL UNIQUE,
    full_name          VARCHAR(100) NOT NULL,
    role               VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER'
    CHECK (role IN ('ROLE_ADMIN', 'ROLE_USER', 'ROLE_AGENT')),
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_locked BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts    INT          NOT NULL DEFAULT 0,
    locked_until       TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_users_username ON app_users(username);
CREATE INDEX IF NOT EXISTS idx_users_email    ON app_users(email);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. refresh_tokens table
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS refresh_tokens (
                                              id          BIGSERIAL PRIMARY KEY,
                                              user_id     BIGINT       NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,       -- SHA-256 hex, 64 chars
    expires_at  TIMESTAMP    NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at  TIMESTAMP,
    device_info VARCHAR(200),
    ip_address  VARCHAR(45),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_rt_token_hash ON refresh_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_rt_user       ON refresh_tokens(user_id);

-- Housekeeping: clean up rows where the token expired more than 7 days ago
-- (run as a scheduled job or add a pg_cron task in production)
-- DELETE FROM refresh_tokens WHERE expires_at < NOW() - INTERVAL '7 days';


-- =============================================================================
-- 3. SEED: First admin user
-- =============================================================================
--
-- Password below is BCrypt hash of: Admin@CIC2026
-- Generated with: BCryptPasswordEncoder(strength=12).encode("Admin@CIC2026")
--
-- HOW TO CHANGE THE SEED PASSWORD:
--   Option A — Use the /auth/register endpoint (if you build one).
--   Option B — Generate a new hash using this Java snippet:
--
--     System.out.println(new BCryptPasswordEncoder(12).encode("YourNewPassword"));
--
--   Option C — Use an online BCrypt generator (strength=12):
--     https://bcrypt-generator.com
--
--   Then UPDATE app_users SET password_hash = '<new-hash>' WHERE username = 'admin';
--
-- INTERN: NEVER store plain text passwords. The hash below is safe to commit
-- because BCrypt is a one-way function — you cannot reverse it to get the password.
-- =============================================================================
INSERT INTO app_users (username, password_hash, email, full_name, role)
VALUES (
           'admin',
           '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCi02diqEEBPMUCMzJWlScW',  -- Admin@CIC2026
           'admin@cic.co.ke',
           'CIC System Admin',
           'ROLE_ADMIN'
       )
    ON CONFLICT (username) DO NOTHING;   -- Safe to re-run: won't duplicate the admin

-- Seed a test agent (for testing ROLE_AGENT permissions)
INSERT INTO app_users (username, password_hash, email, full_name, role)
VALUES (
           'agent01',
           '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCi02diqEEBPMUCMzJWlScW',  -- Admin@CIC2026
           'agent01@cic.co.ke',
           'Test Agent One',
           'ROLE_AGENT'
       )
    ON CONFLICT (username) DO NOTHING;

-- Seed a read-only test user
INSERT INTO app_users (username, password_hash, email, full_name, role)
VALUES (
           'viewer01',
           '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCi02diqEEBPMUCMzJWlScW',  -- Admin@CIC2026
           'viewer01@cic.co.ke',
           'Test Viewer One',
           'ROLE_USER'
       )
    ON CONFLICT (username) DO NOTHING;


-- =============================================================================
-- 4. ROLE MANAGEMENT — SQL you will actually use day-to-day
-- =============================================================================

-- ── View all users and their roles ───────────────────────────────────────────
-- SELECT id, username, email, full_name, role, enabled, account_non_locked,
--        failed_attempts, locked_until, created_at
-- FROM app_users
-- ORDER BY created_at DESC;


-- ── Promote a user to ADMIN ───────────────────────────────────────────────────
-- UPDATE app_users
-- SET role = 'ROLE_ADMIN', updated_at = NOW()
-- WHERE username = 'stephen.ndegwa';


-- ── Demote an ADMIN back to AGENT ────────────────────────────────────────────
-- UPDATE app_users
-- SET role = 'ROLE_AGENT', updated_at = NOW()
-- WHERE username = 'agent01';


-- ── Demote any user to read-only ─────────────────────────────────────────────
-- UPDATE app_users
-- SET role = 'ROLE_USER', updated_at = NOW()
-- WHERE username = 'viewer01';


-- ── Disable an account (blocks login, JWT still works until expiry) ───────────
-- UPDATE app_users
-- SET enabled = FALSE, updated_at = NOW()
-- WHERE username = 'agent01';


-- ── Re-enable a disabled account ─────────────────────────────────────────────
-- UPDATE app_users
-- SET enabled = TRUE, updated_at = NOW()
-- WHERE username = 'agent01';


-- ── Unlock a locked account (too many failed login attempts) ──────────────────
-- UPDATE app_users
-- SET account_non_locked = TRUE,
--     failed_attempts    = 0,
--     locked_until       = NULL,
--     updated_at         = NOW()
-- WHERE username = 'agent01';


-- ── Force-expire all refresh tokens for a user (logs them out everywhere) ─────
-- UPDATE refresh_tokens
-- SET revoked = TRUE, revoked_at = NOW()
-- WHERE user_id = (SELECT id FROM app_users WHERE username = 'agent01')
--   AND revoked = FALSE;


-- ── Reset a user's password (replace hash with BCrypt of new password) ────────
-- UPDATE app_users
-- SET password_hash = '$2a$12$<new-bcrypt-hash-here>',
--     updated_at    = NOW()
-- WHERE username = 'agent01';


-- ── Delete a test user (never delete real users — disable them instead) ────────
-- DELETE FROM app_users WHERE username = 'viewer01';


-- =============================================================================
-- 5. Useful diagnostic queries
-- =============================================================================

-- All currently locked accounts:
-- SELECT username, failed_attempts, locked_until
-- FROM app_users
-- WHERE locked_until > NOW() OR account_non_locked = FALSE;

-- Active refresh tokens per user:
-- SELECT u.username, COUNT(rt.id) AS active_tokens
-- FROM app_users u
-- LEFT JOIN refresh_tokens rt ON rt.user_id = u.id
--   AND rt.revoked = FALSE AND rt.expires_at > NOW()
-- GROUP BY u.username
-- ORDER BY active_tokens DESC;

-- Recent login failures (from audit trail — add your audit log query here):
-- SELECT * FROM audit_logs WHERE action = 'LOGIN_FAIL' ORDER BY changed_at DESC LIMIT 20;