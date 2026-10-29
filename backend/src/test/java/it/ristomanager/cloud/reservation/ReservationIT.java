package it.ristomanager.cloud.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.PostgresTestConfig;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class ReservationIT {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    @Autowired
    MockMvc mvc;

    ApiClient api;
    Actor owner;
    String room;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc);
        owner = api.signup("R");
        room = ApiClient.read(api.post(owner, "/api/v1/rooms", """
                {"name":"Main","widthMeters":12,"heightMeters":9}
                """).andExpect(status().isCreated()), "$.id");
    }

    static LocalDate tomorrow() {
        return LocalDate.now(ROME).plusDays(1);
    }

    /** A time tomorrow in Rome, as the ISO string the API accepts. */
    static String at(int hour, int minute) {
        return ZonedDateTime.of(tomorrow(), java.time.LocalTime.of(hour, minute), ROME).toOffsetDateTime().toString();
    }

    String addTable(int seats) throws Exception {
        return ApiClient.read(api.post(owner, "/api/v1/rooms/" + room + "/tables", """
                {"xMeters":1,"yMeters":1,"widthMeters":1.2,"heightMeters":0.8,"shape":"RECTANGLE","seats":%d}
                """.formatted(seats)).andExpect(status().isCreated()), "$.id");
    }

    static String booking(String guest, String startsAt, int party, String tableId, String status) {
        return """
                {"guestName":"%s","phone":"+39 333 000 1111","startsAt":"%s","partySize":%d,%s"status":"%s"}
                """.formatted(guest, startsAt, party, tableId == null ? "" : "\"tableId\":\"" + tableId + "\",", status);
    }

    @Test
    void aReservationCanBeCreatedReadAndListedForItsDay() throws Exception {
        String table = addTable(4);
        var res = api.post(owner, "/api/v1/reservations", """
                {"guestName":"Bianchi","phone":"+39 333 000 3333","email":"b@example.com","startsAt":"%s",
                 "partySize":4,"highChairs":1,"tableId":"%s","status":"CONFIRMED","allergens":["TREE_NUTS","MILK"],
                 "notes":"Birthday"}
                """.formatted(at(20, 30), table))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationMinutes").value(120))
                .andExpect(jsonPath("$.allergens.length()").value(2))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        String id = ApiClient.read(res, "$.id");

        api.get(owner, "/api/v1/reservations/" + id).andExpect(jsonPath("$.guestName").value("Bianchi"));
        api.get(owner, "/api/v1/reservations?date=" + tomorrow()).andExpect(jsonPath("$.length()").value(1));
        api.get(owner, "/api/v1/reservations?date=" + tomorrow().plusDays(1)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aReservationWithoutPhoneOrEndTimeIsAccepted() throws Exception {
        // Only the date, the time, the number of people and a name are needed; no phone and no end time.
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"Sconosciuto","startsAt":"%s","partySize":3}
                """.formatted(at(20, 0))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value(""))
                .andExpect(jsonPath("$.durationMinutes").value(120));
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"Sconosciuto","phone":"","startsAt":"%s","partySize":3}
                """.formatted(at(22, 30))).andExpect(status().isCreated()).andExpect(jsonPath("$.phone").value(""));
    }

    @Test
    void invalidReservationsAreRejectedWithTheOffendingField() throws Exception {
        api.post(owner, "/api/v1/reservations", """
                {"guestName":" ","phone":"+39 333 000 1111","startsAt":"%s","partySize":2}
                """.formatted(at(20, 0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.guestName").exists());
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"X","phone":"123","startsAt":"%s","partySize":2}
                """.formatted(at(20, 0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.phone").exists());
        String yesterday = ZonedDateTime.of(LocalDate.now(ROME).minusDays(1), java.time.LocalTime.of(20, 0), ROME)
                .toOffsetDateTime().toString();
        api.post(owner, "/api/v1/reservations", booking("X", yesterday, 2, null, "PENDING"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.startsAt").exists());
    }

    @Test
    void theTableMustExistFitTheGroupAndBeInService() throws Exception {
        String small = addTable(2);
        api.post(owner, "/api/v1/reservations", booking("X", at(20, 0), 4, small, "CONFIRMED"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail", containsString("2 seats")));

        api.patch(owner, "/api/v1/tables/" + small + "/service-status", """
                {"serviceStatus":"OUT_OF_SERVICE"}
                """).andExpect(status().isOk());
        api.post(owner, "/api/v1/reservations", booking("X", at(20, 0), 2, small, "CONFIRMED"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail", containsString("out of service")));

        api.post(owner, "/api/v1/reservations", booking("X", at(20, 0), 2, java.util.UUID.randomUUID().toString(), "CONFIRMED"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.tableId").exists());
    }

    @Test
    void aTableCannotBeBookedTwiceAtOverlappingTimes() throws Exception {
        String table = addTable(4);
        api.post(owner, "/api/v1/reservations", booking("Rossi", at(20, 0), 2, table, "CONFIRMED"))
                .andExpect(status().isCreated());

        // Overlaps the end of Rossi's two hours.
        api.post(owner, "/api/v1/reservations", booking("Verdi", at(21, 30), 2, table, "CONFIRMED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("Rossi")))
                .andExpect(jsonPath("$.detail", containsString("20:00")));

        // Starting exactly when Rossi's booking ends is fine.
        api.post(owner, "/api/v1/reservations", booking("Verdi", at(22, 0), 2, table, "CONFIRMED"))
                .andExpect(status().isCreated());
    }

    @Test
    void cancelledReservationsFreeTheirTableAndRevivingRechecks() throws Exception {
        String table = addTable(4);
        String first = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Rossi", at(20, 0), 2, table, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");

        api.patch(owner, "/api/v1/reservations/" + first + "/status", """
                {"status":"CANCELLED"}
                """).andExpect(status().isOk());
        api.post(owner, "/api/v1/reservations", booking("Verdi", at(20, 0), 2, table, "CONFIRMED"))
                .andExpect(status().isCreated());

        // Rossi's table is now taken, so Rossi cannot simply come back.
        api.patch(owner, "/api/v1/reservations/" + first + "/status", """
                {"status":"CONFIRMED"}
                """).andExpect(status().isConflict());
    }

    @Test
    void editingAReservationDoesNotConflictWithItself() throws Exception {
        String table = addTable(4);
        String id = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Rossi", at(20, 0), 2, table, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");
        api.put(owner, "/api/v1/reservations/" + id, """
                {"guestName":"Rossi","phone":"+39 333 000 1111","startsAt":"%s","partySize":3,
                 "tableId":"%s","status":"CONFIRMED","notes":"Window seat"}
                """.formatted(at(20, 15), table)).andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(3));
    }

    @Test
    void parallelBookingsOfTheSameTableLetExactlyOneWin() throws Exception {
        String table = addTable(4);
        int attempts = 16;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String guest = "Guest" + i;
                results.add(pool.submit(() -> api.post(owner, "/api/v1/reservations",
                        booking(guest, at(20, 0), 2, table, "CONFIRMED"))
                        .andReturn().getResponse().getStatus()));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) {
                codes.add(f.get());
            }
            assertThat(codes.stream().filter(c -> c == 201).count()).as("created").isEqualTo(1);
            assertThat(codes.stream().filter(c -> c == 409).count()).as("conflicts").isEqualTo(attempts - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void availableTablesAreSmallestFirstAndSkipBusyOnes() throws Exception {
        String two = addTable(2);
        String four = addTable(4);
        String six = addTable(6);
        // The frontend sends UTC ("...Z"), which also avoids encoding a "+" in the query string.
        String startsAtUtc = OffsetDateTime.parse(at(20, 0)).withOffsetSameInstant(java.time.ZoneOffset.UTC).toString();
        String url = "/api/v1/reservations/available-tables?startsAt=" + startsAtUtc + "&partySize=3";

        api.get(owner, url).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(four)).andExpect(jsonPath("$[1].id").value(six));

        String booked = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Rossi", at(20, 0), 3, four, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");
        api.get(owner, url).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(six));
        // Editing Rossi's own booking, the table it holds counts as free again.
        api.get(owner, url + "&excludeReservationId=" + booked).andExpect(jsonPath("$.length()").value(2));
        assertThat(two).isNotBlank();
    }

    @Test
    void aBadSearchDurationIsA400WithTheFieldNameNotA500() throws Exception {
        addTable(4);
        String startsAtUtc = OffsetDateTime.parse(at(20, 0)).withOffsetSameInstant(java.time.ZoneOffset.UTC).toString();
        String url = "/api/v1/reservations/available-tables?startsAt=" + startsAtUtc + "&partySize=2&durationMinutes=";
        for (String bad : new String[] {"-30", "0", "1441"}) {
            api.get(owner, url + bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields.durationMinutes").exists());
        }
        api.get(owner, url + "1").andExpect(status().isOk());
        api.get(owner, url + "1440").andExpect(status().isOk());
    }

    @Test
    void tooLongGuestFieldsAreRejectedWithTheOffendingField() throws Exception {
        String when = at(20, 0);
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"%s","startsAt":"%s","partySize":2}
                """.formatted("x".repeat(81), when)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.guestName").exists());
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"X","phone":"%s","startsAt":"%s","partySize":2}
                """.formatted("1".repeat(31), when)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.phone").exists());
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"X","email":"%s@example.com","startsAt":"%s","partySize":2}
                """.formatted("a".repeat(120), when)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists());
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"X","notes":"%s","startsAt":"%s","partySize":2}
                """.formatted("n".repeat(501), when)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.notes").exists());
        // Exactly at the limits is accepted.
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"%s","phone":"%s","email":"%s@example.com","notes":"%s","startsAt":"%s","partySize":2}
                """.formatted("x".repeat(80), "1".repeat(30), "a".repeat(108), "n".repeat(500), when))
                .andExpect(status().isCreated());
    }

    @Test
    void rolesDecideWhoCanChangeReservations() throws Exception {
        Actor manager = api.staff(owner, "MANAGER");
        Actor waiter = api.staff(owner, "WAITER");
        Actor kitchen = api.staff(owner, "KITCHEN");
        String id = ApiClient.read(api.post(manager, "/api/v1/reservations", booking("Rossi", at(20, 0), 2, null, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");

        api.get(waiter, "/api/v1/reservations?date=" + tomorrow()).andExpect(status().isOk());
        api.post(waiter, "/api/v1/reservations", booking("X", at(21, 0), 2, null, "PENDING")).andExpect(status().isForbidden());
        api.put(waiter, "/api/v1/reservations/" + id, booking("X", at(21, 0), 2, null, "PENDING")).andExpect(status().isForbidden());
        api.delete(waiter, "/api/v1/reservations/" + id).andExpect(status().isForbidden());
        api.patch(waiter, "/api/v1/reservations/" + id + "/status", """
                {"status":"ARRIVED"}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARRIVED"));
        api.get(kitchen, "/api/v1/reservations?date=" + tomorrow()).andExpect(status().isForbidden());
    }

    private void waiterSets(Actor waiter, String id, String status, int expectedHttp) throws Exception {
        api.patch(waiter, "/api/v1/reservations/" + id + "/status", "{\"status\":\"" + status + "\"}")
                .andExpect(status().is(expectedHttp));
    }

    private String statusOf(String id) throws Exception {
        return ApiClient.read(api.get(owner, "/api/v1/reservations/" + id).andExpect(status().isOk()), "$.status");
    }

    @Test
    void aWaiterCanOnlyMarkArrivedNoShowOrCompleted() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        String id = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Rossi", at(20, 0), 2, null, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");

        // Cancelling, resetting and the same status again are refused, and nothing changes.
        waiterSets(waiter, id, "CANCELLED", 403);
        waiterSets(waiter, id, "PENDING", 403);
        waiterSets(waiter, id, "CONFIRMED", 403);
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
        api.patch(waiter, "/api/v1/reservations/" + id + "/status", "{}").andExpect(status().isForbidden());

        // The moves the day screen offers.
        waiterSets(waiter, id, "ARRIVED", 200);
        waiterSets(waiter, id, "CANCELLED", 403);
        waiterSets(waiter, id, "CONFIRMED", 403);
        assertThat(statusOf(id)).isEqualTo("ARRIVED");
        waiterSets(waiter, id, "COMPLETED", 200);
        // A finished booking cannot be brought back by a waiter.
        waiterSets(waiter, id, "ARRIVED", 403);
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
    }

    @Test
    void aWaiterCannotReviveACancelledBookingButCanMarkANoShow() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        String cancelled = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Verdi", at(20, 0), 2, null, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");
        api.patch(owner, "/api/v1/reservations/" + cancelled + "/status", "{\"status\":\"CANCELLED\"}").andExpect(status().isOk());
        waiterSets(waiter, cancelled, "ARRIVED", 403);
        waiterSets(waiter, cancelled, "CONFIRMED", 403);
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");

        String noShow = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Neri", at(21, 0), 2, null, "PENDING"))
                .andExpect(status().isCreated()), "$.id");
        waiterSets(waiter, noShow, "NO_SHOW", 200);
        assertThat(statusOf(noShow)).isEqualTo("NO_SHOW");
        waiterSets(waiter, java.util.UUID.randomUUID().toString(), "ARRIVED", 404);

        // Owners and managers keep every move, including cancelling and reviving.
        api.patch(owner, "/api/v1/reservations/" + noShow + "/status", "{\"status\":\"CANCELLED\"}").andExpect(status().isOk());
        api.patch(owner, "/api/v1/reservations/" + noShow + "/status", "{\"status\":\"CONFIRMED\"}").andExpect(status().isOk());
    }

    @Test
    void restaurantsCannotSeeOrTouchEachOthersReservations() throws Exception {
        String table = addTable(4);
        String id = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Rossi", at(20, 0), 2, table, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");
        Actor other = api.signup("Other");

        api.get(other, "/api/v1/reservations/" + id).andExpect(status().isNotFound());
        api.get(other, "/api/v1/reservations?date=" + tomorrow()).andExpect(jsonPath("$.length()").value(0));
        api.put(other, "/api/v1/reservations/" + id, booking("Hacked", at(20, 0), 2, null, "CONFIRMED")).andExpect(status().isNotFound());
        api.patch(other, "/api/v1/reservations/" + id + "/status", """
                {"status":"CANCELLED"}
                """).andExpect(status().isNotFound());
        api.delete(other, "/api/v1/reservations/" + id).andExpect(status().isNotFound());
        // Nor can another restaurant's table be assigned to its own guests.
        api.post(other, "/api/v1/reservations", booking("X", at(20, 0), 2, table, "CONFIRMED"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.tableId").exists());

        api.get(owner, "/api/v1/reservations/" + id).andExpect(jsonPath("$.guestName").value("Rossi"));
    }

    @Test
    void deletingATableLeavesItsReservationsUnassigned() throws Exception {
        String table = addTable(4);
        String id = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Rossi", at(20, 0), 2, table, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");
        api.delete(owner, "/api/v1/tables/" + table).andExpect(status().isNoContent());
        api.get(owner, "/api/v1/reservations/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.tableId").doesNotExist());
    }

    @Test
    void theFloorPlanShowsReservedThenOccupiedTables() throws Exception {
        String table = addTable(4);
        OffsetDateTime soon = OffsetDateTime.now().plusMinutes(10);
        String id = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Rossi", soon.toString(), 2, table, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");

        api.get(owner, "/api/v1/rooms/" + room + "/layout").andExpect(jsonPath("$.tables[0].liveStatus").value("RESERVED"));
        api.patch(owner, "/api/v1/reservations/" + id + "/status", """
                {"status":"ARRIVED"}
                """).andExpect(status().isOk());
        api.get(owner, "/api/v1/rooms/" + room + "/layout").andExpect(jsonPath("$.tables[0].liveStatus").value("OCCUPIED"));
    }

    @Test
    void aPartyMarkedAsSittingMakesTheTableOccupiedEvenIfTheBookingIsForLater() throws Exception {
        String table = addTable(4);
        OffsetDateTime evening = OffsetDateTime.now().plusHours(5);
        String id = ApiClient.read(api.post(owner, "/api/v1/reservations", booking("Rossi", evening.toString(), 2, table, "CONFIRMED"))
                .andExpect(status().isCreated()), "$.id");
        // Booked for much later: nothing to show yet.
        api.get(owner, "/api/v1/rooms/" + room + "/layout").andExpect(jsonPath("$.tables[0].liveStatus").value("FREE"));
        // They sit down early: the table is taken now.
        api.patch(owner, "/api/v1/reservations/" + id + "/status", "{\"status\":\"ARRIVED\"}").andExpect(status().isOk());
        api.get(owner, "/api/v1/rooms/" + room + "/layout").andExpect(jsonPath("$.tables[0].liveStatus").value("OCCUPIED"));
        // Marking them done frees it.
        api.patch(owner, "/api/v1/reservations/" + id + "/status", "{\"status\":\"COMPLETED\"}").andExpect(status().isOk());
        api.get(owner, "/api/v1/rooms/" + room + "/layout").andExpect(jsonPath("$.tables[0].liveStatus").value("FREE"));
    }
}
