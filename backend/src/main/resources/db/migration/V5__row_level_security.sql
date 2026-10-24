-- Second lock of the tenant isolation (the first is the restaurant filter in code).
--
-- The API connects as ristomanager_app (see infra/postgres/01-app-role.sh),
-- which is neither superuser nor table owner, so these policies always apply to
-- it. The owner role used by Flyway is exempt, which is what lets the
-- SECURITY DEFINER functions below read across tenants.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ristomanager_app') THEN
        RAISE EXCEPTION 'Role ristomanager_app is missing. Create it first (see infra/postgres/01-app-role.sh).';
    END IF;
END $$;

-- The restaurant of the current request. The API sets app.restaurant_id from the
-- verified login token on every connection checkout. Unset or empty gives NULL,
-- and "restaurant_id = NULL" matches no row: no context means no data.
CREATE FUNCTION current_restaurant_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.restaurant_id', true), '')::uuid $$;

ALTER TABLE restaurant    ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user      ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_token ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON restaurant
    USING (id = current_restaurant_id())
    WITH CHECK (id = current_restaurant_id());

CREATE POLICY tenant_isolation ON app_user
    USING (restaurant_id = current_restaurant_id())
    WITH CHECK (restaurant_id = current_restaurant_id());

CREATE POLICY tenant_isolation ON refresh_token
    USING (restaurant_id = current_restaurant_id())
    WITH CHECK (restaurant_id = current_restaurant_id());

GRANT SELECT, INSERT, UPDATE         ON restaurant    TO ristomanager_app;
GRANT SELECT, INSERT, UPDATE         ON app_user      TO ristomanager_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_token TO ristomanager_app;

-- Before login there is no tenant yet, so a few narrow lookups must cross
-- tenants. They are the only way the app role can see across restaurants, they
-- take one key and return one row (or a boolean), and they are listed here on
-- purpose: review this list whenever it changes.
CREATE FUNCTION auth_find_user_by_email(p_email text)
    RETURNS TABLE (id uuid, restaurant_id uuid, email text, password_hash text,
                   display_name text, role text, active boolean)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT u.id, u.restaurant_id, u.email, u.password_hash, u.display_name, u.role, u.active
      FROM app_user u WHERE u.email = p_email
$$;

CREATE FUNCTION auth_email_exists(p_email text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT EXISTS (SELECT 1 FROM app_user WHERE email = p_email) $$;

CREATE FUNCTION auth_find_refresh_token(p_hash text)
    RETURNS TABLE (id uuid, user_id uuid, restaurant_id uuid, expires_at timestamptz, revoked boolean)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT t.id, t.user_id, t.restaurant_id, t.expires_at, t.revoked
      FROM refresh_token t WHERE t.token_hash = p_hash
$$;

REVOKE ALL ON FUNCTION auth_find_user_by_email(text)  FROM PUBLIC;
REVOKE ALL ON FUNCTION auth_email_exists(text)        FROM PUBLIC;
REVOKE ALL ON FUNCTION auth_find_refresh_token(text)  FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_find_user_by_email(text)  TO ristomanager_app;
GRANT EXECUTE ON FUNCTION auth_email_exists(text)        TO ristomanager_app;
GRANT EXECUTE ON FUNCTION auth_find_refresh_token(text)  TO ristomanager_app;
