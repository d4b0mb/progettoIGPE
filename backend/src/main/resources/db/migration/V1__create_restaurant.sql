-- The tenant root: every other table will carry a restaurant_id pointing here.
CREATE TABLE restaurant (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL CHECK (length(trim(name)) > 0),
    address    text,
    created_at timestamptz NOT NULL DEFAULT now()
);
