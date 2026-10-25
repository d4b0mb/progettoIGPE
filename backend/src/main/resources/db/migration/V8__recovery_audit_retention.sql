-- Owner account recovery, the activity log, and scheduled deletion of old guest data.

-- ---------------------------------------------------------------------------------------------
-- Owner recovery: two questions the owner wrote, with the answers stored only as BCrypt hashes.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE owner_recovery (
    user_id        uuid        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    restaurant_id  uuid        NOT NULL REFERENCES restaurant (id),
    question_1     text        NOT NULL CHECK (length(trim(question_1)) BETWEEN 8 AND 200),
    answer_1_hash  text        NOT NULL,
    question_2     text        NOT NULL CHECK (length(trim(question_2)) BETWEEN 8 AND 200),
    answer_2_hash  text        NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE owner_recovery ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON owner_recovery
    USING (restaurant_id = current_restaurant_id()) WITH CHECK (restaurant_id = current_restaurant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON owner_recovery TO ristomanager_app;

-- ---------------------------------------------------------------------------------------------
-- Activity log. The application can add entries and read its own restaurant's, never change or
-- delete them: there is no UPDATE or DELETE grant. Old entries are removed only by the scheduled
-- retention function below, which runs with the database owner's rights.
-- restaurant_id is empty for events with no restaurant yet (a sign-in attempt with an unknown email).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE audit_event (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id  uuid        REFERENCES restaurant (id),
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_user_id  uuid,
    actor_label    text        NOT NULL DEFAULT '',
    action         text        NOT NULL,
    target_type    text,
    target_id      text,
    outcome        text        NOT NULL DEFAULT 'SUCCESS' CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    details        jsonb       NOT NULL DEFAULT '{}',
    ip             text,
    user_agent     text
);
CREATE INDEX audit_event_restaurant_time_idx ON audit_event (restaurant_id, occurred_at DESC, id DESC);
CREATE INDEX audit_event_time_idx ON audit_event (occurred_at);

ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_read ON audit_event FOR SELECT USING (restaurant_id = current_restaurant_id());
CREATE POLICY tenant_write ON audit_event FOR INSERT WITH CHECK (restaurant_id = current_restaurant_id());
GRANT SELECT, INSERT ON audit_event TO ristomanager_app;

-- ---------------------------------------------------------------------------------------------
-- The few things that must cross restaurants (like the lookups in V5). Each is listed here on purpose.
-- ---------------------------------------------------------------------------------------------

-- Recovery starts from an email address, before any restaurant is known. Only active owners
-- who have set up recovery are returned.
CREATE FUNCTION auth_find_recovery(p_email text)
    RETURNS TABLE (user_id uuid, restaurant_id uuid, question_1 text, answer_1_hash text,
                   question_2 text, answer_2_hash text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT r.user_id, r.restaurant_id, r.question_1, r.answer_1_hash, r.question_2, r.answer_2_hash
      FROM owner_recovery r JOIN app_user u ON u.id = r.user_id
     WHERE u.email = p_email AND u.active AND u.role = 'OWNER'
$$;

-- A log entry for something that has no restaurant: a failed sign-in for an unknown email,
-- or the nightly cleanup itself.
CREATE FUNCTION audit_record_unscoped(p_actor_label text, p_action text, p_outcome text,
                                      p_details jsonb, p_ip text, p_user_agent text)
    RETURNS void
    LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    INSERT INTO audit_event (restaurant_id, actor_label, action, outcome, details, ip, user_agent)
    VALUES (NULL, left(p_actor_label, 254), left(p_action, 80), p_outcome, p_details, left(p_ip, 64), left(p_user_agent, 300))
$$;

-- Nightly cleanup. Guest personal data (name, phone, email, notes, declared allergies, which are
-- health data) is erased once a visit ended more than p_guest_days ago; the booking itself stays
-- so counts and history still add up. Log entries older than p_audit_days are deleted, and so are
-- long-dead login tokens.
CREATE FUNCTION retention_purge(p_guest_days integer, p_audit_days integer)
    RETURNS TABLE (reservations_anonymised integer, tickets_anonymised integer,
                   audit_deleted integer, tokens_deleted integer)
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp
AS $$
DECLARE
    r integer; t integer; a integer; k integer;
BEGIN
    IF p_guest_days < 1 OR p_audit_days < 1 THEN
        RAISE EXCEPTION 'retention periods must be at least one day';
    END IF;

    UPDATE reservation
       SET guest_name = 'Anonymised', phone = '', email = NULL, notes = '', allergens = '{}'
     WHERE ends_at < now() - make_interval(days => p_guest_days)
       AND (guest_name <> 'Anonymised' OR phone <> '' OR email IS NOT NULL OR notes <> '' OR allergens <> '{}');
    GET DIAGNOSTICS r = ROW_COUNT;

    UPDATE kitchen_ticket
       SET guest_name = '', guest_allergens = '{}'
     WHERE created_at < now() - make_interval(days => p_guest_days)
       AND (guest_name <> '' OR guest_allergens <> '{}');
    GET DIAGNOSTICS t = ROW_COUNT;

    DELETE FROM audit_event WHERE occurred_at < now() - make_interval(days => p_audit_days);
    GET DIAGNOSTICS a = ROW_COUNT;

    DELETE FROM refresh_token WHERE expires_at < now() - interval '7 days';
    GET DIAGNOSTICS k = ROW_COUNT;

    RETURN QUERY SELECT r, t, a, k;
END;
$$;

REVOKE ALL ON FUNCTION auth_find_recovery(text)                                   FROM PUBLIC;
REVOKE ALL ON FUNCTION audit_record_unscoped(text, text, text, jsonb, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION retention_purge(integer, integer)                          FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_find_recovery(text)                                   TO ristomanager_app;
GRANT EXECUTE ON FUNCTION audit_record_unscoped(text, text, text, jsonb, text, text) TO ristomanager_app;
GRANT EXECUTE ON FUNCTION retention_purge(integer, integer)                          TO ristomanager_app;
