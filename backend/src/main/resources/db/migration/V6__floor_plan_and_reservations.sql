-- Floor plan (rooms, tables, non-bookable elements) and reservations.
--
-- Three safety nets protect these tables:
--   1. composite foreign keys (id, restaurant_id) so a row can never point at
--      another restaurant's room or table, even if the code had a bug;
--   2. row-level security (same policy as every tenant table);
--   3. an exclusion constraint that makes double-booking a table impossible,
--      even when two people book it at the same instant.

CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE restaurant ADD COLUMN timezone text NOT NULL DEFAULT 'Europe/Rome';

CREATE TABLE room (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id uuid         NOT NULL REFERENCES restaurant (id),
    name          text         NOT NULL CHECK (length(trim(name)) > 0),
    width_m       numeric(6,2) NOT NULL CHECK (width_m  BETWEEN 1 AND 200),
    height_m      numeric(6,2) NOT NULL CHECK (height_m BETWEEN 1 AND 200),
    grid_step_m   numeric(4,2) NOT NULL DEFAULT 0.25 CHECK (grid_step_m > 0),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (id, restaurant_id),
    UNIQUE (restaurant_id, name)
);

CREATE TABLE dining_table (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id  uuid         NOT NULL REFERENCES restaurant (id),
    room_id        uuid         NOT NULL,
    number         integer      NOT NULL CHECK (number > 0),
    name           text         NOT NULL DEFAULT '',
    x_m            numeric(8,3) NOT NULL,
    y_m            numeric(8,3) NOT NULL,
    width_m        numeric(6,3) NOT NULL CHECK (width_m  > 0),
    height_m       numeric(6,3) NOT NULL CHECK (height_m > 0),
    rotation_deg   numeric(6,2) NOT NULL DEFAULT 0,
    seats          integer      NOT NULL CHECK (seats BETWEEN 1 AND 100),
    shape          text         NOT NULL CHECK (shape IN ('ROUND', 'SQUARE', 'RECTANGLE')),
    accessible     boolean      NOT NULL DEFAULT false,
    service_status text         NOT NULL DEFAULT 'FREE'
                   CHECK (service_status IN ('FREE', 'TO_CLEAN', 'OUT_OF_SERVICE')),
    UNIQUE (id, restaurant_id),
    UNIQUE (restaurant_id, number),
    FOREIGN KEY (room_id, restaurant_id) REFERENCES room (id, restaurant_id)
);

CREATE TABLE obstacle (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id uuid         NOT NULL REFERENCES restaurant (id),
    room_id       uuid         NOT NULL,
    type          text         NOT NULL CHECK (type IN ('WALL', 'COLUMN', 'DOOR', 'WINDOW', 'STAIRS',
                                                       'KITCHEN', 'TOILETS', 'BAR', 'ENTRANCE', 'STORAGE')),
    name          text         NOT NULL DEFAULT '',
    x_m           numeric(8,3) NOT NULL,
    y_m           numeric(8,3) NOT NULL,
    width_m       numeric(6,3) NOT NULL CHECK (width_m  > 0),
    height_m      numeric(6,3) NOT NULL CHECK (height_m > 0),
    rotation_deg  numeric(6,2) NOT NULL DEFAULT 0,
    FOREIGN KEY (room_id, restaurant_id) REFERENCES room (id, restaurant_id)
);

CREATE TABLE reservation (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id   uuid        NOT NULL REFERENCES restaurant (id),
    table_id        uuid,
    guest_name      text        NOT NULL CHECK (length(trim(guest_name)) > 0),
    phone           text        NOT NULL,
    email           text,
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    party_size      integer     NOT NULL CHECK (party_size BETWEEN 1 AND 40),
    high_chairs     integer     NOT NULL DEFAULT 0 CHECK (high_chairs >= 0),
    stroller_spaces integer     NOT NULL DEFAULT 0 CHECK (stroller_spaces BETWEEN 0 AND 6),
    status          text        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'CONFIRMED', 'ARRIVED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    allergens       text[]      NOT NULL DEFAULT '{}',
    notes           text        NOT NULL DEFAULT '',
    created_by      uuid        REFERENCES app_user (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at - starts_at BETWEEN interval '15 minutes' AND interval '12 hours'),
    CHECK (high_chairs <= party_size),
    UNIQUE (id, restaurant_id),
    -- If a table is deleted the reservation stays, unassigned.
    FOREIGN KEY (table_id, restaurant_id) REFERENCES dining_table (id, restaurant_id) ON DELETE SET NULL (table_id),
    -- A table cannot hold two live reservations that overlap in time.
    CONSTRAINT reservation_no_double_booking
        EXCLUDE USING gist (table_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
        WHERE (table_id IS NOT NULL AND status IN ('PENDING', 'CONFIRMED', 'ARRIVED'))
);

CREATE INDEX room_restaurant_idx          ON room (restaurant_id);
CREATE INDEX dining_table_room_idx        ON dining_table (room_id);
CREATE INDEX obstacle_room_idx            ON obstacle (room_id);
CREATE INDEX reservation_restaurant_start ON reservation (restaurant_id, starts_at);

ALTER TABLE room         ENABLE ROW LEVEL SECURITY;
ALTER TABLE dining_table ENABLE ROW LEVEL SECURITY;
ALTER TABLE obstacle     ENABLE ROW LEVEL SECURITY;
ALTER TABLE reservation  ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON room
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());
CREATE POLICY tenant_isolation ON dining_table
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());
CREATE POLICY tenant_isolation ON obstacle
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());
CREATE POLICY tenant_isolation ON reservation
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON room, dining_table, obstacle, reservation TO ristomanager_app;
