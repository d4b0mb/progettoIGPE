package it.ristomanager.cloud.reservation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.reservation.ReservationTypes.Draft;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReservationRulesTest {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final Instant NOW = Instant.parse("2026-10-24T18:00:00Z"); // 20:00 in Rome

    private static Draft valid() {
        return new Draft("Rossi", "+39 333 000 1111", "rossi@example.com",
                OffsetDateTime.parse("2026-10-24T20:30:00+02:00"), 120, 4, 0, 0, null,
                ReservationStatus.CONFIRMED, List.of(), "");
    }

    private static Draft with(java.util.function.UnaryOperator<DraftBuilder> change) {
        return change.apply(new DraftBuilder(valid())).build();
    }

    /** Tiny builder so each test changes exactly one field. */
    private static final class DraftBuilder {
        String guest, phone, email, notes;
        OffsetDateTime startsAt;
        Integer duration, party, chairs, strollers;
        ReservationStatus status;

        DraftBuilder(Draft d) {
            guest = d.guestName(); phone = d.phone(); email = d.email(); notes = d.notes();
            startsAt = d.startsAt(); duration = d.durationMinutes(); party = d.partySize();
            chairs = d.highChairs(); strollers = d.strollerSpaces(); status = d.status();
        }

        Draft build() {
            return new Draft(guest, phone, email, startsAt, duration, party, chairs, strollers, null, status,
                    List.of(), notes);
        }
    }

    private static void assertRejected(Draft d, boolean isNew, String field) {
        assertThatThrownBy(() -> ReservationRules.validate(d, ROME, NOW, isNew))
                .isInstanceOfSatisfying(ApiException.class, e -> org.assertj.core.api.Assertions.assertThat(e.field()).isEqualTo(field));
    }

    @Test
    void aTypicalReservationIsValid() {
        assertThatCode(() -> ReservationRules.validate(valid(), ROME, NOW, true)).doesNotThrowAnyException();
    }

    @Test
    void guestNameIsRequired() {
        assertRejected(with(b -> { b.guest = "  "; return b; }), true, "guestName");
        assertRejected(with(b -> { b.guest = null; return b; }), true, "guestName");
    }

    @Test
    void newReservationsCannotBeOnAPastDayButTodayEarlierIsFine() {
        Draft yesterday = with(b -> { b.startsAt = OffsetDateTime.parse("2026-10-23T20:30:00+02:00"); return b; });
        assertRejected(yesterday, true, "startsAt");

        Draft earlierToday = with(b -> { b.startsAt = OffsetDateTime.parse("2026-10-24T12:30:00+02:00"); return b; });
        assertThatCode(() -> ReservationRules.validate(earlierToday, ROME, NOW, true)).doesNotThrowAnyException();
    }

    @Test
    void anExistingPastReservationCanStillBeUpdated() {
        Draft yesterday = with(b -> { b.startsAt = OffsetDateTime.parse("2026-10-23T20:30:00+02:00"); return b; });
        assertThatCode(() -> ReservationRules.validate(yesterday, ROME, NOW, false)).doesNotThrowAnyException();
    }

    @Test
    void theDayIsJudgedInTheRestaurantsTimeZoneNotUtc() {
        // 00:30 on the 25th in Rome is still the 24th in UTC. Now is the evening of the 24th in Rome.
        Draft afterMidnight = with(b -> { b.startsAt = OffsetDateTime.parse("2026-10-25T00:30:00+02:00"); return b; });
        assertThatCode(() -> ReservationRules.validate(afterMidnight, ROME, NOW, true)).doesNotThrowAnyException();
    }

    @Test
    void durationMustBeBetween15MinutesAnd12Hours() {
        assertRejected(with(b -> { b.duration = 14; return b; }), true, "durationMinutes");
        assertRejected(with(b -> { b.duration = 721; return b; }), true, "durationMinutes");
        assertThatCode(() -> ReservationRules.validate(with(b -> { b.duration = 15; return b; }), ROME, NOW, true))
                .doesNotThrowAnyException();
        assertThatCode(() -> ReservationRules.validate(with(b -> { b.duration = 720; return b; }), ROME, NOW, true))
                .doesNotThrowAnyException();
    }

    @Test
    void durationDefaultsToTwoHoursWhenOmitted() {
        org.assertj.core.api.Assertions.assertThat(
                ReservationRules.durationOf(with(b -> { b.duration = null; return b; }))).isEqualTo(120);
    }

    @Test
    void partySizeIsBetween1And40() {
        assertRejected(with(b -> { b.party = 0; return b; }), true, "partySize");
        assertRejected(with(b -> { b.party = null; return b; }), true, "partySize");
        assertRejected(with(b -> { b.party = 41; return b; }), true, "partySize");
        assertThatCode(() -> ReservationRules.validate(with(b -> { b.party = 40; return b; }), ROME, NOW, true))
                .doesNotThrowAnyException();
    }

    @Test
    void highChairsCannotExceedGuests() {
        assertRejected(with(b -> { b.party = 2; b.chairs = 3; return b; }), true, "highChairs");
        assertRejected(with(b -> { b.chairs = -1; return b; }), true, "highChairs");
    }

    @Test
    void strollerCountMustBePlausible() {
        assertRejected(with(b -> { b.strollers = 7; return b; }), true, "strollerSpaces");
    }

    @Test
    void phoneNeedsAtLeastSixDigits() {
        assertRejected(with(b -> { b.phone = "12345"; return b; }), true, "phone");
        assertRejected(with(b -> { b.phone = "abc"; return b; }), true, "phone");
        assertThatCode(() -> ReservationRules.validate(with(b -> { b.phone = "+39 0984 12 34"; return b; }), ROME, NOW, true))
                .doesNotThrowAnyException();
    }

    @Test
    void emailIsOptionalButMustLookRightWhenGiven() {
        assertThatCode(() -> ReservationRules.validate(with(b -> { b.email = null; return b; }), ROME, NOW, true))
                .doesNotThrowAnyException();
        assertThatCode(() -> ReservationRules.validate(with(b -> { b.email = ""; return b; }), ROME, NOW, true))
                .doesNotThrowAnyException();
        assertRejected(with(b -> { b.email = "not-an-email"; return b; }), true, "email");
    }

    @Test
    void statusDefaultsToPending() {
        org.assertj.core.api.Assertions.assertThat(
                ReservationRules.statusOf(with(b -> { b.status = null; return b; }))).isEqualTo(ReservationStatus.PENDING);
    }
}
