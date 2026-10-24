-- Staff accounts. Every user belongs to exactly one restaurant.
-- Emails are stored lowercase and are unique across the whole service,
-- so a person can log in without naming their restaurant first.
CREATE TABLE app_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id uuid        NOT NULL REFERENCES restaurant (id),
    email         text        NOT NULL CHECK (email = lower(email)),
    password_hash text        NOT NULL,
    display_name  text        NOT NULL CHECK (length(trim(display_name)) > 0),
    role          text        NOT NULL CHECK (role IN ('OWNER', 'MANAGER', 'WAITER', 'KITCHEN')),
    active        boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (email)
);

CREATE INDEX app_user_restaurant_idx ON app_user (restaurant_id);
