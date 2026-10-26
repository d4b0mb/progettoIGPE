-- Toppings, removals and other options a guest can add to a dish ("+ bufala", "senza basilico", "non tagliata").
-- An option belongs to a course (the pizza options only show on pizzas), carries a price and the allergens it brings
-- with it, so a pistachio topping on a pizza reaches the kitchen's allergy alarm.
CREATE TABLE menu_modifier (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id uuid        NOT NULL REFERENCES restaurant (id),
    name          text        NOT NULL CHECK (length(trim(name)) > 0),
    course        text        NOT NULL CHECK (course IN ('STARTER', 'PIZZA', 'FIRST', 'SECOND', 'SIDE', 'DESSERT', 'DRINK')),
    section       text        NOT NULL DEFAULT '' CHECK (length(section) <= 60),
    price_cents   integer     NOT NULL DEFAULT 0 CHECK (price_cents >= 0),
    allergens     text[]      NOT NULL DEFAULT '{}',
    available     boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, restaurant_id),
    UNIQUE (restaurant_id, course, name)
);

CREATE INDEX menu_modifier_restaurant_idx ON menu_modifier (restaurant_id, course);

ALTER TABLE menu_modifier ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON menu_modifier
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON menu_modifier TO ristomanager_app;

-- What was chosen is copied onto the kitchen ticket, like the dish name, so it stays right if the menu changes later.
ALTER TABLE ticket_item
    ADD COLUMN modifiers      text[]  NOT NULL DEFAULT '{}',
    ADD COLUMN modifier_cents integer NOT NULL DEFAULT 0 CHECK (modifier_cents >= 0);
