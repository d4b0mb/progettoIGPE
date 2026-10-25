-- Servings ("portate"): an order can be split into several servings that the kitchen sends out one after
-- the other (first the starters, then the steak, then the pasta). A serving is only a number on each line;
-- the ticket remembers which serving is being cooked now. When it is served, the next one goes to the kitchen.
ALTER TABLE ticket_item
    ADD COLUMN serving smallint NOT NULL DEFAULT 1 CHECK (serving BETWEEN 1 AND 9);

ALTER TABLE kitchen_ticket
    ADD COLUMN current_serving smallint    NOT NULL DEFAULT 1 CHECK (current_serving BETWEEN 1 AND 9),
    ADD COLUMN serving_since   timestamptz NOT NULL DEFAULT now();

UPDATE kitchen_ticket SET serving_since = created_at;

-- Pizzerias sell pizza: a course of its own. A menu section ("Birre", "Vini", "Distillati") groups
-- the many items of one course on screen.
ALTER TABLE menu_item DROP CONSTRAINT menu_item_course_check;
ALTER TABLE menu_item ADD CONSTRAINT menu_item_course_check
    CHECK (course IN ('STARTER', 'PIZZA', 'FIRST', 'SECOND', 'SIDE', 'DESSERT', 'DRINK'));
ALTER TABLE ticket_item DROP CONSTRAINT ticket_item_course_check;
ALTER TABLE ticket_item ADD CONSTRAINT ticket_item_course_check
    CHECK (course IN ('STARTER', 'PIZZA', 'FIRST', 'SECOND', 'SIDE', 'DESSERT', 'DRINK'));
ALTER TABLE menu_item ADD COLUMN section text NOT NULL DEFAULT '' CHECK (length(section) <= 60);

-- Each restaurant can pick how the app looks.
ALTER TABLE restaurant
    ADD COLUMN theme text NOT NULL DEFAULT 'classic' CHECK (theme IN ('classic', 'duodecim'));
