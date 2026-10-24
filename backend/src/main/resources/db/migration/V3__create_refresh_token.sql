-- Refresh tokens are stored only as a SHA-256 hash, never in clear.
-- Each use rotates the token; presenting an already-used token is treated
-- as theft and revokes every token of that user.
CREATE TABLE refresh_token (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked    boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);
