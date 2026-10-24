-- Row-level security needs every tenant-owned table to carry restaurant_id.
ALTER TABLE refresh_token ADD COLUMN restaurant_id uuid;

UPDATE refresh_token t
   SET restaurant_id = u.restaurant_id
  FROM app_user u
 WHERE u.id = t.user_id;

ALTER TABLE refresh_token
    ALTER COLUMN restaurant_id SET NOT NULL,
    ADD CONSTRAINT refresh_token_restaurant_fk FOREIGN KEY (restaurant_id) REFERENCES restaurant (id);

CREATE INDEX refresh_token_restaurant_idx ON refresh_token (restaurant_id);
