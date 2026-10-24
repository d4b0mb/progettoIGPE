-- Menu, kitchen tickets (orders) and their lines.
-- Same safety nets as before: composite foreign keys keep every row inside its
-- restaurant, and row-level security applies to the application role.

CREATE TABLE menu_item (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id uuid        NOT NULL REFERENCES restaurant (id),
    name          text        NOT NULL CHECK (length(trim(name)) > 0),
    description   text        NOT NULL DEFAULT '',
    course        text        NOT NULL CHECK (course IN ('STARTER', 'FIRST', 'SECOND', 'SIDE', 'DESSERT', 'DRINK')),
    price_cents   integer     CHECK (price_cents >= 0),
    allergens     text[]      NOT NULL DEFAULT '{}',
    available     boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, restaurant_id),
    UNIQUE (restaurant_id, name)
);

-- One ticket = one order sent to the kitchen. Name, table number and allergens
-- are copied onto it, so it stays accurate even if the menu or floor plan change later.
CREATE TABLE kitchen_ticket (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id    uuid        NOT NULL REFERENCES restaurant (id),
    ticket_day       date        NOT NULL,
    code             text        NOT NULL,
    table_id         uuid,
    table_number     integer,
    reservation_id   uuid,
    guest_name       text        NOT NULL DEFAULT '',
    guest_allergens  text[]      NOT NULL DEFAULT '{}',
    priority         text        NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT', 'VIP')),
    status           text        NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'PREPARING', 'READY', 'SERVED', 'CANCELLED')),
    note             text        NOT NULL DEFAULT '',
    created_by       uuid        REFERENCES app_user (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    acknowledged_at  timestamptz,
    ready_at         timestamptz,
    served_at        timestamptz,
    UNIQUE (id, restaurant_id),
    UNIQUE (restaurant_id, ticket_day, code),
    FOREIGN KEY (table_id, restaurant_id) REFERENCES dining_table (id, restaurant_id) ON DELETE SET NULL (table_id),
    FOREIGN KEY (reservation_id, restaurant_id) REFERENCES reservation (id, restaurant_id) ON DELETE SET NULL (reservation_id)
);

CREATE TABLE ticket_item (
    id            uuid     PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id uuid     NOT NULL REFERENCES restaurant (id),
    ticket_id     uuid     NOT NULL,
    menu_item_id  uuid,
    name          text     NOT NULL,
    quantity      integer  NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    course        text     NOT NULL CHECK (course IN ('STARTER', 'FIRST', 'SECOND', 'SIDE', 'DESSERT', 'DRINK')),
    allergens     text[]   NOT NULL DEFAULT '{}',
    note          text     NOT NULL DEFAULT '',
    FOREIGN KEY (ticket_id, restaurant_id) REFERENCES kitchen_ticket (id, restaurant_id) ON DELETE CASCADE,
    FOREIGN KEY (menu_item_id, restaurant_id) REFERENCES menu_item (id, restaurant_id) ON DELETE SET NULL (menu_item_id)
);

-- Daily running number for ticket codes (C-001, C-002 ...), one row per restaurant per day.
-- Incremented atomically, so simultaneous orders never get the same code.
CREATE TABLE ticket_counter (
    restaurant_id uuid    NOT NULL REFERENCES restaurant (id),
    day           date    NOT NULL,
    last_number   integer NOT NULL,
    PRIMARY KEY (restaurant_id, day)
);

CREATE INDEX menu_item_restaurant_idx      ON menu_item (restaurant_id);
CREATE INDEX kitchen_ticket_active_idx     ON kitchen_ticket (restaurant_id, status, created_at);
CREATE INDEX ticket_item_ticket_idx        ON ticket_item (ticket_id);

ALTER TABLE menu_item      ENABLE ROW LEVEL SECURITY;
ALTER TABLE kitchen_ticket ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_item    ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_counter ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON menu_item
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());
CREATE POLICY tenant_isolation ON kitchen_ticket
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());
CREATE POLICY tenant_isolation ON ticket_item
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());
CREATE POLICY tenant_isolation ON ticket_counter
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON menu_item, kitchen_ticket, ticket_item, ticket_counter TO ristomanager_app;
