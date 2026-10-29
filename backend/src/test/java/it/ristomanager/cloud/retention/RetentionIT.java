package it.ristomanager.cloud.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.OwnerDb;
import it.ristomanager.cloud.support.PostgresTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class RetentionIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    RetentionService retention;

    @Autowired
    PostgreSQLContainer postgres;

    private UUID insertReservation(OwnerDb db, String restaurant, String guest, int endedDaysAgo) {
        UUID id = UUID.randomUUID();
        db.run("""
                insert into reservation (id, restaurant_id, guest_name, phone, email, starts_at, ends_at, party_size,
                                         status, allergens, notes)
                values (?, ?::uuid, ?, '+39 333 111 2222', 'guest@example.com',
                        now() - make_interval(days => ?) - interval '2 hours', now() - make_interval(days => ?),
                        2, 'COMPLETED', '{MILK}', 'window seat, nut allergy')
                """, id, restaurant, guest, endedDaysAgo, endedDaysAgo);
        return id;
    }

    @Test
    void oldGuestDetailsAreErasedAndRecentOnesAreLeftAlone() throws Exception {
        ApiClient api = new ApiClient(mvc);
        Actor a = api.signup("A");
        Actor b = api.signup("B");
        try (OwnerDb db = new OwnerDb(postgres)) {
            UUID old = insertReservation(db, a.restaurantId(), "Rossi", 200);
            UUID justOver = insertReservation(db, a.restaurantId(), "Bianchi", 91);
            UUID recent = insertReservation(db, a.restaurantId(), "Verdi", 10);
            UUID otherRestaurantOld = insertReservation(db, b.restaurantId(), "Greco", 300);

            RetentionService.Result result = retention.purge(90, 365);
            assertThat(result.reservationsAnonymised()).isGreaterThanOrEqualTo(3);

            for (UUID id : new UUID[] {old, justOver, otherRestaurantOld}) {
                assertThat(db.one("select guest_name from reservation where id = ?::uuid", String.class, id.toString())).isEqualTo("Anonymised");
                assertThat(db.one("select phone from reservation where id = ?::uuid", String.class, id.toString())).isEmpty();
                assertThat(db.one("select email from reservation where id = ?::uuid", String.class, id.toString())).isNull();
                assertThat(db.one("select notes from reservation where id = ?::uuid", String.class, id.toString())).isEmpty();
                assertThat(db.count("select cardinality(allergens) from reservation where id = ?::uuid", id.toString())).isZero();
            }
            // The visit itself is still counted: party size and times stay.
            assertThat(db.count("select party_size from reservation where id = ?::uuid", old.toString())).isEqualTo(2);
            // Ten days ago is within the 90-day period.
            assertThat(db.one("select guest_name from reservation where id = ?::uuid", String.class, recent.toString())).isEqualTo("Verdi");
            assertThat(db.one("select email from reservation where id = ?::uuid", String.class, recent.toString())).isEqualTo("guest@example.com");

            // Running it again finds nothing more to do.
            assertThat(retention.purge(90, 365).reservationsAnonymised()).isZero();
        }
    }

    @Test
    void oldKitchenTicketsLoseTheGuestNameAndAllergiesButKeepTheOrder() throws Exception {
        Actor a = new ApiClient(mvc).signup("A");
        try (OwnerDb db = new OwnerDb(postgres)) {
            UUID old = UUID.randomUUID();
            UUID recent = UUID.randomUUID();
            for (UUID id : new UUID[] {old, recent}) {
                db.run("""
                        insert into kitchen_ticket (id, restaurant_id, ticket_day, code, guest_name, guest_allergens, created_at)
                        values (?, ?::uuid, current_date, ?, 'Rossi', '{MILK}', now() - make_interval(days => ?))
                        """, id, a.restaurantId(), "C-" + id.toString().substring(0, 6), id.equals(old) ? 200 : 5);
            }
            retention.purge(90, 365);
            assertThat(db.one("select guest_name from kitchen_ticket where id = ?::uuid", String.class, old.toString())).isEmpty();
            assertThat(db.count("select cardinality(guest_allergens) from kitchen_ticket where id = ?::uuid", old.toString())).isZero();
            assertThat(db.count("select count(*) from kitchen_ticket where id = ?::uuid", old.toString())).isEqualTo(1);
            assertThat(db.one("select guest_name from kitchen_ticket where id = ?::uuid", String.class, recent.toString())).isEqualTo("Rossi");
        }
    }

    @Test
    void oldLogEntriesAndDeadTokensAreDeleted() throws Exception {
        Actor a = new ApiClient(mvc).signup("A");
        try (OwnerDb db = new OwnerDb(postgres)) {
            UUID oldEntry = UUID.randomUUID();
            UUID recentEntry = UUID.randomUUID();
            db.run("insert into audit_event (id, restaurant_id, action, occurred_at) values (?, ?::uuid, 'OLD_THING', now() - interval '400 days')", oldEntry, a.restaurantId());
            db.run("insert into audit_event (id, restaurant_id, action, occurred_at) values (?, ?::uuid, 'RECENT_THING', now() - interval '100 days')", recentEntry, a.restaurantId());
            db.run("insert into refresh_token (user_id, restaurant_id, token_hash, expires_at) values (?::uuid, ?::uuid, ?, now() - interval '30 days')",
                    a.userId(), a.restaurantId(), "dead-" + UUID.randomUUID());

            RetentionService.Result result = retention.purge(90, 365);
            assertThat(result.auditDeleted()).isGreaterThanOrEqualTo(1);
            assertThat(result.tokensDeleted()).isGreaterThanOrEqualTo(1);
            assertThat(db.count("select count(*) from audit_event where id = ?::uuid", oldEntry.toString())).isZero();
            assertThat(db.count("select count(*) from audit_event where id = ?::uuid", recentEntry.toString())).isEqualTo(1);
            // The sign-in that is still valid is untouched.
            assertThat(db.count("select count(*) from refresh_token where user_id = ?::uuid and expires_at > now()", a.userId())).isGreaterThanOrEqualTo(1);
        }
    }

    private UUID insertTicket(OwnerDb db, String restaurant, String guest, String note, int createdDaysAgo) {
        UUID id = UUID.randomUUID();
        db.run("""
                insert into kitchen_ticket (id, restaurant_id, ticket_day, code, guest_name, guest_allergens, note, created_at)
                values (?, ?::uuid, current_date, ?, ?, '{MILK}', ?, now() - make_interval(days => ?))
                """, id, restaurant, "C-" + id.toString().substring(0, 6), guest, note, createdDaysAgo);
        return id;
    }

    private UUID insertLine(OwnerDb db, String restaurant, UUID ticket, String note) {
        UUID id = UUID.randomUUID();
        db.run("""
                insert into ticket_item (id, restaurant_id, ticket_id, name, quantity, course, note)
                values (?, ?::uuid, ?, 'Pesto trofie', 2, 'FIRST', ?)
                """, id, restaurant, ticket, note);
        return id;
    }

    @Test
    void notesOfOldTicketsAndTheirLinesAreErasedButRecentOnesStay() throws Exception {
        Actor a = new ApiClient(mvc).signup("A");
        try (OwnerDb db = new OwnerDb(postgres)) {
            UUID old = insertTicket(db, a.restaurantId(), "Rossi", "for Mrs Rossi, no nuts", 200);
            UUID oldLine = insertLine(db, a.restaurantId(), old, "Rossi is allergic to pistachio");
            // Nothing else left to erase on this ticket, only the note: it must still be cleared.
            UUID oldNoteOnly = insertTicket(db, a.restaurantId(), "", "table of Mr Bianchi", 150);
            db.run("update kitchen_ticket set guest_allergens = '{}' where id = ?::uuid", oldNoteOnly.toString());
            // A ticket that is already blank except for the note of one line.
            UUID oldLineOnly = insertTicket(db, a.restaurantId(), "", "", 120);
            db.run("update kitchen_ticket set guest_allergens = '{}' where id = ?::uuid", oldLineOnly.toString());
            UUID oldLineOnlyItem = insertLine(db, a.restaurantId(), oldLineOnly, "for Verdi");
            UUID recent = insertTicket(db, a.restaurantId(), "Greco", "birthday cake for Greco", 5);
            UUID recentLine = insertLine(db, a.restaurantId(), recent, "no onion");

            retention.purge(90, 365);

            assertThat(db.one("select note from kitchen_ticket where id = ?::uuid", String.class, old.toString())).isEmpty();
            assertThat(db.one("select guest_name from kitchen_ticket where id = ?::uuid", String.class, old.toString())).isEmpty();
            assertThat(db.one("select note from kitchen_ticket where id = ?::uuid", String.class, oldNoteOnly.toString())).isEmpty();
            assertThat(db.one("select note from ticket_item where id = ?::uuid", String.class, oldLine.toString())).isEmpty();
            assertThat(db.one("select note from ticket_item where id = ?::uuid", String.class, oldLineOnlyItem.toString())).isEmpty();
            // The order itself is kept: the dish, the quantity, and the ticket.
            assertThat(db.one("select name from ticket_item where id = ?::uuid", String.class, oldLine.toString())).isEqualTo("Pesto trofie");
            assertThat(db.count("select quantity from ticket_item where id = ?::uuid", oldLine.toString())).isEqualTo(2);
            assertThat(db.count("select count(*) from kitchen_ticket where id = ?::uuid", old.toString())).isEqualTo(1);

            // Within the period nothing is touched.
            assertThat(db.one("select note from kitchen_ticket where id = ?::uuid", String.class, recent.toString())).isEqualTo("birthday cake for Greco");
            assertThat(db.one("select note from ticket_item where id = ?::uuid", String.class, recentLine.toString())).isEqualTo("no onion");
        }
    }

    @Test
    void revokedTokensAreDeletedAfterSevenDaysAndLiveOnesAreKept() throws Exception {
        Actor a = new ApiClient(mvc).signup("A");
        try (OwnerDb db = new OwnerDb(postgres)) {
            String revokedOld = "revoked-old-" + UUID.randomUUID();
            String revokedRecent = "revoked-recent-" + UUID.randomUUID();
            String liveOld = "live-old-" + UUID.randomUUID();
            // All three are still unexpired (valid for 5 more days), so only the revoked flag and age decide.
            for (Object[] row : new Object[][] {{revokedOld, true, 10}, {revokedRecent, true, 2}, {liveOld, false, 10}}) {
                db.run("""
                        insert into refresh_token (user_id, restaurant_id, token_hash, expires_at, revoked, created_at)
                        values (?::uuid, ?::uuid, ?, now() + interval '5 days', ?, now() - make_interval(days => ?))
                        """, a.userId(), a.restaurantId(), row[0], row[1], row[2]);
            }

            RetentionService.Result result = retention.purge(90, 365);

            assertThat(result.tokensDeleted()).isGreaterThanOrEqualTo(1);
            assertThat(db.count("select count(*) from refresh_token where token_hash = ?", revokedOld)).isZero();
            // Revoked a short while ago: kept, because it is still of use for detecting a replayed token.
            assertThat(db.count("select count(*) from refresh_token where token_hash = ?", revokedRecent)).isEqualTo(1);
            assertThat(db.count("select count(*) from refresh_token where token_hash = ?", liveOld)).isEqualTo(1);
        }
    }

    @Test
    void nonsensicalRetentionPeriodsAreRefused() {
        assertThatThrownBy(() -> retention.purge(0, 365)).rootCause().hasMessageContaining("retention periods must be at least one day");
        assertThatThrownBy(() -> retention.purge(90, 0)).isInstanceOf(RuntimeException.class);
    }
}
