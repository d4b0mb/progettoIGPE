-- Retention, second version (replaces the function from V8; same name, arguments and result columns, so the
-- application's call is unchanged).
--
-- What V8 missed:
--   * the free-text note of a kitchen ticket and of each ticket line can hold a guest's name or an allergy detail
--     ("for Mrs Rossi, no nuts"), so they are erased together with the rest of the ticket's guest data;
--   * a refresh token that was revoked (signed out, rotated, password changed) stays useless but was kept until it
--     also expired plus 7 days; revoked tokens are now deleted 7 days after they were created.
-- Everything else is exactly as in V8. Same security conventions as the other SECURITY DEFINER functions:
-- pinned search_path, no rights for PUBLIC, execute right for the application role only.

CREATE OR REPLACE FUNCTION retention_purge(p_guest_days integer, p_audit_days integer)
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

    -- Reservations: name becomes 'Anonymised'; phone, email, notes and allergens are emptied.
    UPDATE reservation
       SET guest_name = 'Anonymised', phone = '', email = NULL, notes = '', allergens = '{}'
     WHERE ends_at < now() - make_interval(days => p_guest_days)
       AND (guest_name <> 'Anonymised' OR phone <> '' OR email IS NOT NULL OR notes <> '' OR allergens <> '{}');
    GET DIAGNOSTICS r = ROW_COUNT;

    -- Notes of the lines of old tickets (the dish, quantity and options stay).
    UPDATE ticket_item
       SET note = ''
     WHERE note <> ''
       AND ticket_id IN (SELECT id FROM kitchen_ticket
                          WHERE created_at < now() - make_interval(days => p_guest_days));

    -- Old tickets: guest name, declared allergens and the ticket's own note are emptied; the order stays.
    -- The count is of tickets changed here; lines whose note was cleared are not counted separately.
    UPDATE kitchen_ticket
       SET guest_name = '', guest_allergens = '{}', note = ''
     WHERE created_at < now() - make_interval(days => p_guest_days)
       AND (guest_name <> '' OR guest_allergens <> '{}' OR note <> '');
    GET DIAGNOSTICS t = ROW_COUNT;

    DELETE FROM audit_event WHERE occurred_at < now() - make_interval(days => p_audit_days);
    GET DIAGNOSTICS a = ROW_COUNT;

    -- Tokens that expired more than 7 days ago, and tokens that were revoked and were created more than 7 days ago.
    DELETE FROM refresh_token
     WHERE expires_at < now() - interval '7 days'
        OR (revoked AND created_at < now() - interval '7 days');
    GET DIAGNOSTICS k = ROW_COUNT;

    RETURN QUERY SELECT r, t, a, k;
END;
$$;

REVOKE ALL ON FUNCTION retention_purge(integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION retention_purge(integer, integer) TO ristomanager_app;
