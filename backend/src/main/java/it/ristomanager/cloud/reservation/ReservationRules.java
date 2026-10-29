package it.ristomanager.cloud.reservation;

import static it.ristomanager.cloud.common.ApiException.invalid;

import it.ristomanager.cloud.reservation.ReservationTypes.ReservationRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * The rules about what a valid reservation looks like, carried over from the
 * desktop RistoManager. Pure functions with no database, so they are fast to test.
 * Rules that need other data (table size, overlaps) live in {@link ReservationService}.
 */
public final class ReservationRules {

    public static final int MIN_DURATION_MINUTES = 15;
    public static final int MAX_DURATION_MINUTES = 12 * 60;
    public static final int DEFAULT_DURATION_MINUTES = 120;
    public static final int MAX_PARTY_SIZE = 40;
    public static final int MAX_STROLLERS = 6;
    public static final int MIN_SEARCH_DURATION_MINUTES = 1;
    public static final int MAX_SEARCH_DURATION_MINUTES = 24 * 60;
    /** The columns are unbounded text, so these limits are the only guard against absurd input. */
    public static final int MAX_GUEST_NAME_LENGTH = 80;
    public static final int MAX_PHONE_LENGTH = 30;
    public static final int MAX_EMAIL_LENGTH = 120;
    public static final int MAX_NOTES_LENGTH = 500;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private ReservationRules() {
    }

    /**
     * @param isNew a new reservation cannot be on a past day; an existing one can
     *              still be updated (marked completed or no-show) the day after.
     */
    public static void validate(ReservationRequest d, ZoneId zone, Instant now, boolean isNew) {
        if (d.guestName() == null || d.guestName().isBlank()) {
            throw invalid("guestName", "The guest name is required");
        }
        if (d.guestName().length() > MAX_GUEST_NAME_LENGTH) {
            throw invalid("guestName", "The guest name is too long (at most " + MAX_GUEST_NAME_LENGTH + " characters)");
        }
        if (d.startsAt() == null) {
            throw invalid("startsAt", "Date and time are required");
        }
        if (isNew) {
            LocalDate day = d.startsAt().toInstant().atZone(zone).toLocalDate();
            if (day.isBefore(now.atZone(zone).toLocalDate())) {
                throw invalid("startsAt", "A new reservation cannot be on a day that has passed");
            }
        }
        int duration = durationOf(d);
        if (duration < MIN_DURATION_MINUTES || duration > MAX_DURATION_MINUTES) {
            throw invalid("durationMinutes", "The duration must be between 15 minutes and 12 hours");
        }
        if (d.partySize() == null || d.partySize() < 1) {
            throw invalid("partySize", "The party must be at least 1 guest");
        }
        if (d.partySize() > MAX_PARTY_SIZE) {
            throw invalid("partySize", "Groups over 40 need an event booking");
        }
        int highChairs = d.highChairs() == null ? 0 : d.highChairs();
        if (highChairs < 0 || highChairs > d.partySize()) {
            throw invalid("highChairs", "High chairs cannot exceed the number of guests");
        }
        int strollers = d.strollerSpaces() == null ? 0 : d.strollerSpaces();
        if (strollers < 0 || strollers > MAX_STROLLERS) {
            throw invalid("strollerSpaces", "That number of strollers is not plausible");
        }
        // A phone number is welcome but not required: the screen warns when it is missing. If one is given, it must be real.
        if (d.phone() != null && d.phone().length() > MAX_PHONE_LENGTH) {
            throw invalid("phone", "The phone number is too long (at most " + MAX_PHONE_LENGTH + " characters)");
        }
        if (d.phone() != null && !d.phone().isBlank() && d.phone().replaceAll("\\D", "").length() < 6) {
            throw invalid("phone", "Enter a valid phone number (at least 6 digits)");
        }
        if (d.email() != null && d.email().length() > MAX_EMAIL_LENGTH) {
            throw invalid("email", "The email address is too long (at most " + MAX_EMAIL_LENGTH + " characters)");
        }
        if (d.email() != null && !d.email().isBlank() && !EMAIL.matcher(d.email().trim()).matches()) {
            throw invalid("email", "The email address is not valid");
        }
        if (d.notes() != null && d.notes().length() > MAX_NOTES_LENGTH) {
            throw invalid("notes", "The notes are too long (at most " + MAX_NOTES_LENGTH + " characters)");
        }
    }

    /**
     * The length of a table search ("which tables are free for this long"): when given, between 1 minute and 24 hours.
     * Wider than a reservation's own range on purpose, because a search is only a question and writes nothing; what it
     * must never do is build a time range that ends before it starts.
     */
    public static void validateSearchDuration(Integer durationMinutes) {
        if (durationMinutes != null && (durationMinutes < MIN_SEARCH_DURATION_MINUTES
                || durationMinutes > MAX_SEARCH_DURATION_MINUTES)) {
            throw invalid("durationMinutes", "The duration must be between " + MIN_SEARCH_DURATION_MINUTES
                    + " minute and 24 hours");
        }
    }

    public static int durationOf(ReservationRequest d) {
        return d.durationMinutes() == null ? DEFAULT_DURATION_MINUTES : d.durationMinutes();
    }

    public static ReservationStatus statusOf(ReservationRequest d) {
        return d.status() == null ? ReservationStatus.PENDING : d.status();
    }
}
