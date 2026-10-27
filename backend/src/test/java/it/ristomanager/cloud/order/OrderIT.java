package it.ristomanager.cloud.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.OwnerDb;
import it.ristomanager.cloud.support.PostgresTestConfig;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class OrderIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    ApiClient api;
    Actor owner;
    Actor waiter;
    Actor kitchen;
    String table;
    String water;
    String pesto;
    String steak;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc);
        owner = api.signup("R");
        waiter = api.staff(owner, "WAITER");
        kitchen = api.staff(owner, "KITCHEN");
        String room = ApiClient.read(api.post(owner, "/api/v1/rooms", """
                {"name":"Main","widthMeters":12,"heightMeters":9}
                """).andExpect(status().isCreated()), "$.id");
        table = ApiClient.read(api.post(owner, "/api/v1/rooms/" + room + "/tables", """
                {"xMeters":1,"yMeters":1,"widthMeters":1.2,"heightMeters":0.8,"shape":"RECTANGLE","seats":4}
                """).andExpect(status().isCreated()), "$.id");
        water = dish("Water", "DRINK", "[]");
        pesto = dish("Pesto trofie", "FIRST", "[\"TREE_NUTS\",\"GLUTEN\"]");
        steak = dish("Steak", "SECOND", "[]");
    }

    String dish(String name, String course, String allergens) throws Exception {
        return ApiClient.read(api.post(owner, "/api/v1/menu/items", """
                {"name":"%s","course":"%s","allergens":%s,"priceCents":900}
                """.formatted(name, course, allergens)).andExpect(status().isCreated()), "$.id");
    }

    static String order(String table, String priority, String... menuItemIds) {
        StringBuilder lines = new StringBuilder();
        for (String id : menuItemIds) {
            if (lines.length() > 0) {
                lines.append(',');
            }
            lines.append("{\"menuItemId\":\"").append(id).append("\",\"quantity\":2}");
        }
        return """
                {"tableId":"%s","priority":"%s","items":[%s]}
                """.formatted(table, priority, lines);
    }

    @Test
    void anOrderCarriesTheMenusAllergensNotTheClients() throws Exception {
        api.post(waiter, "/api/v1/orders", """
                {"tableId":"%s","items":[{"menuItemId":"%s","quantity":1,"allergens":["FISH"],"name":"Hacked"}]}
                """.formatted(table, pesto))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("C-001"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.priority").value("NORMAL"))
                .andExpect(jsonPath("$.items[0].name").value("Pesto trofie"))
                .andExpect(jsonPath("$.items[0].allergens.length()").value(2));
    }

    @Test
    void guestAllergiesAreCrossedWithTheDishes() throws Exception {
        // A party has arrived at the table and declared a nut allergy.
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"Bianchi","phone":"+39 333 000 3333","startsAt":"%s","partySize":2,"tableId":"%s",
                 "status":"ARRIVED","allergens":["TREE_NUTS"]}
                """.formatted(OffsetDateTime.now().minusMinutes(5), table)).andExpect(status().isCreated());

        api.post(waiter, "/api/v1/orders", order(table, "NORMAL", pesto, steak))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.guestName").value("Bianchi"))
                .andExpect(jsonPath("$.guestAllergens[0]").value("TREE_NUTS"))
                .andExpect(jsonPath("$.hasAllergyConflicts").value(true))
                .andExpect(jsonPath("$.items[?(@.name=='Pesto trofie')].conflicts[0]").value("TREE_NUTS"))
                .andExpect(jsonPath("$.items[?(@.name=='Steak')].conflicts.length()").value(0));
    }

    /** A party with a nut allergy, marked as arrived at the table, booked to start at the given moment. Returns its id. */
    private String arrivedParty(String name, OffsetDateTime startsAt) throws Exception {
        return ApiClient.read(api.post(owner, "/api/v1/reservations", """
                {"guestName":"%s","phone":"+39 333 000 3333","startsAt":"%s","partySize":2,"tableId":"%s",
                 "status":"ARRIVED","allergens":["TREE_NUTS"]}
                """.formatted(name, startsAt, table)).andExpect(status().isCreated()), "$.id");
    }

    /**
     * Moves the booking so it started that long ago (and so ended two hours after that, the usual stay), as the database
     * owner: the API refuses a booking on a day that has passed.
     */
    private void startedAgo(String reservationId, String interval) {
        try (OwnerDb db = new OwnerDb(postgres)) {
            db.run("update reservation set starts_at = now() - cast(? as interval), "
                            + "ends_at = now() - cast(? as interval) + interval '2 hours' where id = ?",
                    interval, interval, UUID.fromString(reservationId));
        }
    }

    private void assertNutAllergyReachesTheKitchen(String guest) throws Exception {
        api.post(waiter, "/api/v1/orders", order(table, "NORMAL", pesto, steak))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.guestName").value(guest))
                .andExpect(jsonPath("$.guestAllergens[0]").value("TREE_NUTS"))
                .andExpect(jsonPath("$.hasAllergyConflicts").value(true))
                .andExpect(jsonPath("$.items[?(@.name=='Pesto trofie')].conflicts[0]").value("TREE_NUTS"));
    }

    @Test
    void aPartyThatArrivedBeforeItsBookedTimeStillGetsTheAllergyAlarm() throws Exception {
        arrivedParty("Early", OffsetDateTime.now().plusMinutes(45));

        assertNutAllergyReachesTheKitchen("Early");
    }

    @Test
    void aPartyStayingPastItsBookedEndStillGetsTheAllergyAlarm() throws Exception {
        String id = arrivedParty("Late", OffsetDateTime.now().plusMinutes(1));
        startedAgo(id, "3 hours"); // booked for 2 hours, so it ended an hour ago and the party is still at the table

        assertNutAllergyReachesTheKitchen("Late");
    }

    @Test
    void anArrivalForgottenOnTheFloorForHalfADayIsNotTakenForTheCurrentGuests() throws Exception {
        String id = arrivedParty("Forgotten", OffsetDateTime.now().plusMinutes(1));
        startedAgo(id, "14 hours");

        api.post(waiter, "/api/v1/orders", order(table, "NORMAL", pesto))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.guestName").value(""))
                .andExpect(jsonPath("$.guestAllergens.length()").value(0))
                .andExpect(jsonPath("$.hasAllergyConflicts").value(false));
    }

    @Test
    void whenAnOldPartyWasNeverClosedTheNearestOneIsTheCurrentGuest() throws Exception {
        String old = arrivedParty("Old", OffsetDateTime.now().plusMinutes(1));
        startedAgo(old, "3 hours");
        arrivedParty("Current", OffsetDateTime.now().minusMinutes(5));

        assertNutAllergyReachesTheKitchen("Current");
    }

    @Test
    void anOrderWithNoGuestAllergiesHasNoConflicts() throws Exception {
        api.post(waiter, "/api/v1/orders", order(table, "NORMAL", pesto))
                .andExpect(jsonPath("$.hasAllergyConflicts").value(false));
    }

    @Test
    void invalidOrdersAreRejected() throws Exception {
        api.post(waiter, "/api/v1/orders", """
                {"items":[{"menuItemId":"%s","quantity":1}]}
                """.formatted(steak)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.tableId").exists());
        api.post(waiter, "/api/v1/orders", """
                {"tableId":"%s","items":[]}
                """.formatted(table)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.items").exists());
        api.post(waiter, "/api/v1/orders", """
                {"tableId":"%s","items":[{"menuItemId":"%s","quantity":0}]}
                """.formatted(table, steak)).andExpect(status().isBadRequest());
        api.post(waiter, "/api/v1/orders", """
                {"tableId":"%s","items":[{"menuItemId":"%s","quantity":1}]}
                """.formatted(table, java.util.UUID.randomUUID())).andExpect(status().isBadRequest());

        api.put(owner, "/api/v1/menu/items/" + steak, """
                {"name":"Steak","course":"SECOND","available":false}
                """).andExpect(status().isOk());
        api.post(waiter, "/api/v1/orders", order(table, "NORMAL", steak))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail", containsString("not available")));
    }

    @Test
    void theKitchenQueueIsOrderedByPriorityThenAge() throws Exception {
        api.post(waiter, "/api/v1/orders", order(table, "NORMAL", water)).andExpect(status().isCreated());
        api.post(waiter, "/api/v1/orders", order(table, "VIP", water)).andExpect(status().isCreated());
        api.post(waiter, "/api/v1/orders", order(table, "NORMAL", steak)).andExpect(status().isCreated());
        api.post(waiter, "/api/v1/orders", order(table, "URGENT", steak)).andExpect(status().isCreated());

        api.get(kitchen, "/api/v1/orders/active").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].priority").value("VIP"))
                .andExpect(jsonPath("$[1].priority").value("URGENT"))
                .andExpect(jsonPath("$[2].code").value("C-001"))
                .andExpect(jsonPath("$[3].code").value("C-003"));
    }

    @Test
    void ticketsMoveThroughTheKitchenWithTimestampsAndRoleRules() throws Exception {
        String id = ApiClient.read(api.post(waiter, "/api/v1/orders", order(table, "NORMAL", steak))
                .andExpect(status().isCreated()), "$.id");
        String url = "/api/v1/orders/" + id + "/status";

        // The kitchen cooks; the waiter cannot.
        api.patch(waiter, url, "{\"status\":\"PREPARING\"}").andExpect(status().isForbidden());
        api.patch(kitchen, url, "{\"status\":\"PREPARING\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt").exists());
        // Cannot jump a step, nor serve before it is ready.
        api.patch(owner, url, "{\"status\":\"SERVED\"}").andExpect(status().isConflict());
        api.patch(kitchen, url, "{\"status\":\"READY\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.readyAt").exists());
        // The kitchen cannot serve; the waiter can.
        api.patch(kitchen, url, "{\"status\":\"SERVED\"}").andExpect(status().isForbidden());
        api.patch(waiter, url, "{\"status\":\"SERVED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.servedAt").exists());
        // Finished tickets leave the screen and cannot be reopened.
        api.get(kitchen, "/api/v1/orders/active").andExpect(jsonPath("$.length()").value(0));
        api.patch(owner, url, "{\"status\":\"PREPARING\"}").andExpect(status().isConflict());
    }

    @Test
    void aWaiterCanCancelANewOrderButNotOneBeingServed() throws Exception {
        String id = ApiClient.read(api.post(waiter, "/api/v1/orders", order(table, "NORMAL", steak))
                .andExpect(status().isCreated()), "$.id");
        api.patch(waiter, "/api/v1/orders/" + id + "/status", "{\"status\":\"CANCELLED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        api.patch(waiter, "/api/v1/orders/" + id + "/status", "{\"status\":\"SERVED\"}").andExpect(status().isConflict());
    }

    @Test
    void kitchenStaffCannotPlaceOrders() throws Exception {
        api.post(kitchen, "/api/v1/orders", order(table, "NORMAL", steak)).andExpect(status().isForbidden());
    }

    @Test
    void restaurantsCannotSeeOrTouchEachOthersOrdersOrMenus() throws Exception {
        String id = ApiClient.read(api.post(waiter, "/api/v1/orders", order(table, "NORMAL", steak))
                .andExpect(status().isCreated()), "$.id");
        Actor other = api.signup("Other");

        api.get(other, "/api/v1/orders/" + id).andExpect(status().isNotFound());
        api.get(other, "/api/v1/orders/active").andExpect(jsonPath("$.length()").value(0));
        api.patch(other, "/api/v1/orders/" + id + "/status", "{\"status\":\"CANCELLED\"}").andExpect(status().isNotFound());
        api.get(other, "/api/v1/menu/items").andExpect(jsonPath("$.length()").value(0));
        api.put(other, "/api/v1/menu/items/" + steak, "{\"name\":\"X\",\"course\":\"SECOND\"}").andExpect(status().isNotFound());
        api.delete(other, "/api/v1/menu/items/" + steak).andExpect(status().isNotFound());
        // Their staff cannot order from our menu or for our table either.
        Actor otherWaiter = api.staff(other, "WAITER");
        api.post(otherWaiter, "/api/v1/orders", order(table, "NORMAL", steak)).andExpect(status().isBadRequest());
    }

    @Test
    void aDishWithoutAPriceHasNoPriceNotZero() throws Exception {
        String free = ApiClient.read(api.post(owner, "/api/v1/menu/items", "{\"name\":\"Bread\",\"course\":\"SIDE\"}")
                .andExpect(status().isCreated()), "$.id");
        api.post(owner, "/api/v1/menu/items", "{\"name\":\"Free sample\",\"course\":\"SIDE\",\"priceCents\":0}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.priceCents").value(0));
        api.get(owner, "/api/v1/menu/items").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='Bread')].priceCents").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$[?(@.name=='Free sample')].priceCents").value(org.hamcrest.Matchers.contains(0)));
        assertThat(free).isNotBlank();
    }

    @Test
    void menuIsReadableByAllStaffButEditableOnlyByOwnerAndManager() throws Exception {
        api.get(kitchen, "/api/v1/menu/items").andExpect(status().isOk());
        api.get(waiter, "/api/v1/menu/items").andExpect(status().isOk());
        api.post(waiter, "/api/v1/menu/items", "{\"name\":\"X\",\"course\":\"DRINK\"}").andExpect(status().isForbidden());
        api.post(owner, "/api/v1/menu/items", "{\"name\":\"Water\",\"course\":\"DRINK\"}").andExpect(status().isConflict());
    }

    /** Ported from the desktop project: many stations sending at once must never share a code or id. */
    @Test
    void sixtyParallelOrdersGetDistinctCodesAndIds() throws Exception {
        int stations = 4;
        int perStation = 15;
        List<Actor> senders = new ArrayList<>();
        for (int i = 0; i < stations; i++) {
            senders.add(api.staff(owner, "WAITER"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(stations);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<List<String[]>>> results = new ArrayList<>();
            for (Actor sender : senders) {
                results.add(pool.submit(() -> {
                    go.await();
                    List<String[]> made = new ArrayList<>();
                    for (int n = 0; n < perStation; n++) {
                        var res = api.post(sender, "/api/v1/orders", order(table, "NORMAL", water))
                                .andExpect(status().isCreated());
                        made.add(new String[] {ApiClient.read(res, "$.id"), ApiClient.read(res, "$.code")});
                    }
                    return made;
                }));
            }
            go.countDown();
            Set<String> ids = new HashSet<>();
            Set<String> codes = new HashSet<>();
            for (Future<List<String[]>> f : results) {
                for (String[] made : f.get()) {
                    ids.add(made[0]);
                    codes.add(made[1]);
                }
            }
            assertThat(ids).hasSize(stations * perStation);
            assertThat(codes).hasSize(stations * perStation);
            // Codes run C-001 to C-060 with no gaps.
            for (int n = 1; n <= stations * perStation; n++) {
                assertThat(codes).contains("C-%03d".formatted(n));
            }
            api.get(kitchen, "/api/v1/orders/active").andExpect(jsonPath("$.length()").value(stations * perStation));
        } finally {
            pool.shutdownNow();
        }
    }

    private String servingOrder(String... idServing) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < idServing.length; i += 2) {
            if (lines.length() > 0) {
                lines.append(',');
            }
            lines.append("{\"menuItemId\":\"").append(idServing[i]).append("\",\"quantity\":1,\"serving\":")
                    .append(idServing[i + 1]).append('}');
        }
        return "{\"tableId\":\"%s\",\"items\":[%s]}".formatted(table, lines);
    }

    @Test
    void servingsGoToTheKitchenOneAfterTheOtherWithoutLosingTheRestOfTheOrder() throws Exception {
        String id = ApiClient.read(api.post(waiter, "/api/v1/orders", servingOrder(water, "1", pesto, "2", steak, "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentServing").value(1))
                .andExpect(jsonPath("$.servingCount").value(3))
                .andExpect(jsonPath("$.items[0].serving").value(1))
                .andExpect(jsonPath("$.items[2].serving").value(3)), "$.id");
        String url = "/api/v1/orders/" + id + "/status";

        // Serving 1 is cooked and served ...
        api.patch(kitchen, url, "{\"status\":\"PREPARING\"}").andExpect(status().isOk());
        api.patch(kitchen, url, "{\"status\":\"READY\"}").andExpect(status().isOk());
        // ... and serving it does not finish the order: serving 2 is now new for the kitchen.
        api.patch(waiter, url, "{\"status\":\"SERVED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.currentServing").value(2))
                .andExpect(jsonPath("$.servingCount").value(3))
                .andExpect(jsonPath("$.acknowledgedAt").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(3));
        api.get(kitchen, "/api/v1/orders/active").andExpect(jsonPath("$.length()").value(1));

        api.patch(kitchen, url, "{\"status\":\"PREPARING\"}").andExpect(status().isOk());
        api.patch(kitchen, url, "{\"status\":\"READY\"}").andExpect(status().isOk());
        api.patch(waiter, url, "{\"status\":\"SERVED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.currentServing").value(3)).andExpect(jsonPath("$.status").value("NEW"));

        // The last serving finishes the order.
        api.patch(kitchen, url, "{\"status\":\"PREPARING\"}").andExpect(status().isOk());
        api.patch(kitchen, url, "{\"status\":\"READY\"}").andExpect(status().isOk());
        api.patch(waiter, url, "{\"status\":\"SERVED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SERVED")).andExpect(jsonPath("$.servedAt").exists());
        api.get(kitchen, "/api/v1/orders/active").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void servingNumbersMayHaveGapsAndDefaultToOne() throws Exception {
        // Only servings 2 and 5 are used: the order starts at 2 and then moves to 5.
        String id = ApiClient.read(api.post(waiter, "/api/v1/orders", servingOrder(pesto, "2", steak, "5"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.currentServing").value(2))
                .andExpect(jsonPath("$.servingCount").value(2)), "$.id");
        String url = "/api/v1/orders/" + id + "/status";
        api.patch(kitchen, url, "{\"status\":\"PREPARING\"}").andExpect(status().isOk());
        api.patch(kitchen, url, "{\"status\":\"READY\"}").andExpect(status().isOk());
        api.patch(waiter, url, "{\"status\":\"SERVED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.currentServing").value(5));

        // No serving given means serving 1.
        api.post(waiter, "/api/v1/orders", order(table, "NORMAL", steak)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentServing").value(1)).andExpect(jsonPath("$.servingCount").value(1))
                .andExpect(jsonPath("$.items[0].serving").value(1));
    }

    @Test
    void invalidServingNumbersAreRefusedAndCancellingStopsTheWholeOrder() throws Exception {
        api.post(waiter, "/api/v1/orders", servingOrder(steak, "0")).andExpect(status().isBadRequest());
        api.post(waiter, "/api/v1/orders", servingOrder(steak, "10")).andExpect(status().isBadRequest());

        String id = ApiClient.read(api.post(waiter, "/api/v1/orders", servingOrder(pesto, "1", steak, "2"))
                .andExpect(status().isCreated()), "$.id");
        api.patch(waiter, "/api/v1/orders/" + id + "/status", "{\"status\":\"CANCELLED\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        api.get(kitchen, "/api/v1/orders/active").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void pizzasAndMenuSectionsWorkAndTheOwnerPicksTheLook() throws Exception {
        api.post(owner, "/api/v1/menu/items", """
                {"name":"Margherita","course":"PIZZA","section":"Pizze classiche","priceCents":700,"allergens":["GLUTEN","MILK"]}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.course").value("PIZZA"))
                .andExpect(jsonPath("$.section").value("Pizze classiche"));
        api.post(owner, "/api/v1/menu/items", "{\"name\":\"Plain\",\"course\":\"DRINK\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.section").value(""));
        api.post(owner, "/api/v1/menu/items", "{\"name\":\"Long\",\"course\":\"DRINK\",\"section\":\"" + "x".repeat(61) + "\"}")
                .andExpect(status().isBadRequest());

        api.get(waiter, "/api/v1/restaurant").andExpect(status().isOk()).andExpect(jsonPath("$.theme").value("CLASSIC"))
                .andExpect(jsonPath("$.name").value("R"));
        api.put(waiter, "/api/v1/restaurant/theme", "{\"theme\":\"DUODECIM\"}").andExpect(status().isForbidden());
        api.put(owner, "/api/v1/restaurant/theme", "{\"theme\":\"DUODECIM\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("DUODECIM"));
        api.get(kitchen, "/api/v1/restaurant").andExpect(jsonPath("$.theme").value("DUODECIM"));
        api.put(owner, "/api/v1/restaurant/theme", "{\"theme\":\"NEON\"}").andExpect(status().isBadRequest());
    }
}
