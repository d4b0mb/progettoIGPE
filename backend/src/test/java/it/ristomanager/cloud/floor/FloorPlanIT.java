package it.ristomanager.cloud.floor;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.PostgresTestConfig;
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
class FloorPlanIT {

    @Autowired
    MockMvc mvc;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
    }

    static String table(String extra) {
        return """
                {"xMeters":2,"yMeters":3,"widthMeters":1.2,"heightMeters":0.8,"shape":"RECTANGLE"%s}
                """.formatted(extra);
    }

    String createRoom(Actor who, String name) throws Exception {
        return ApiClient.read(api.post(who, "/api/v1/rooms",
                """
                {"name":"%s","widthMeters":12,"heightMeters":9}
                """.formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    @Test
    void ownerBuildsARoomWithTablesAndElements() throws Exception {
        Actor owner = api.signup("R");
        String room = createRoom(owner, "Main room");

        api.post(owner, "/api/v1/rooms/" + room + "/tables", table(",\"number\":1,\"seats\":4"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(1));
        api.post(owner, "/api/v1/rooms/" + room + "/obstacles", """
                {"type":"BAR","xMeters":0,"yMeters":0,"widthMeters":4,"heightMeters":0.8}
                """).andExpect(status().isCreated());

        api.get(owner, "/api/v1/rooms/" + room + "/layout").andExpect(status().isOk())
                .andExpect(jsonPath("$.room.name").value("Main room"))
                .andExpect(jsonPath("$.tables.length()").value(1))
                .andExpect(jsonPath("$.tables[0].liveStatus").value("FREE"))
                .andExpect(jsonPath("$.obstacles[0].type").value("BAR"));
    }

    @Test
    void allTablesListsEveryRoomsTablesOfThisRestaurantOnly() throws Exception {
        Actor owner = api.signup("R");
        String main = createRoom(owner, "Main");
        String terrace = createRoom(owner, "Terrace");
        api.post(owner, "/api/v1/rooms/" + main + "/tables", table("")).andExpect(status().isCreated());
        api.post(owner, "/api/v1/rooms/" + terrace + "/tables", table("")).andExpect(status().isCreated());
        createRoom(api.signup("Other"), "Main");

        api.get(owner, "/api/v1/tables").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        api.get(api.staff(owner, "KITCHEN"), "/api/v1/tables").andExpect(status().isForbidden());
    }

    @Test
    void tableNumbersAreAssignedAutomaticallyAndMustBeUnique() throws Exception {
        Actor owner = api.signup("R");
        String room = createRoom(owner, "Main");
        String url = "/api/v1/rooms/" + room + "/tables";

        api.post(owner, url, table("")).andExpect(jsonPath("$.number").value(1));
        api.post(owner, url, table("")).andExpect(jsonPath("$.number").value(2));
        api.post(owner, url, table(",\"number\":2")).andExpect(status().isConflict());
    }

    @Test
    void seatsAreSuggestedAndRoundTablesStayRound() throws Exception {
        Actor owner = api.signup("R");
        String room = createRoom(owner, "Main");
        api.post(owner, "/api/v1/rooms/" + room + "/tables", """
                {"xMeters":1,"yMeters":1,"widthMeters":1.2,"heightMeters":3,"shape":"ROUND"}
                """).andExpect(status().isCreated())
                .andExpect(jsonPath("$.heightMeters").value(1.2))
                .andExpect(jsonPath("$.seats").value(6));
    }

    @Test
    void roomNamesAreUniquePerRestaurantAndInvalidSizesAreRejected() throws Exception {
        Actor owner = api.signup("R");
        createRoom(owner, "Terrace");
        api.post(owner, "/api/v1/rooms", """
                {"name":"Terrace","widthMeters":5,"heightMeters":5}
                """).andExpect(status().isConflict());
        api.post(owner, "/api/v1/rooms", """
                {"name":"Tiny","widthMeters":-1,"heightMeters":5}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.widthMeters").exists());
        // Another restaurant may reuse the name.
        createRoom(api.signup("Other"), "Terrace");
    }

    @Test
    void aRoomWithTablesCannotBeDeletedUntilItIsEmpty() throws Exception {
        Actor owner = api.signup("R");
        String room = createRoom(owner, "Main");
        String tableId = ApiClient.read(api.post(owner, "/api/v1/rooms/" + room + "/tables", table(""))
                .andExpect(status().isCreated()), "$.id");

        api.delete(owner, "/api/v1/rooms/" + room).andExpect(status().isConflict());
        api.delete(owner, "/api/v1/tables/" + tableId).andExpect(status().isNoContent());
        api.delete(owner, "/api/v1/rooms/" + room).andExpect(status().isNoContent());
    }

    @Test
    void rolesDecideWhoCanReadAndWho_CanEdit() throws Exception {
        Actor owner = api.signup("R");
        String room = createRoom(owner, "Main");
        String tableId = ApiClient.read(api.post(owner, "/api/v1/rooms/" + room + "/tables", table(""))
                .andExpect(status().isCreated()), "$.id");
        Actor manager = api.staff(owner, "MANAGER");
        Actor waiter = api.staff(owner, "WAITER");
        Actor kitchen = api.staff(owner, "KITCHEN");

        api.get(waiter, "/api/v1/rooms/" + room + "/layout").andExpect(status().isOk());
        api.post(waiter, "/api/v1/rooms/" + room + "/tables", table("")).andExpect(status().isForbidden());
        api.delete(waiter, "/api/v1/tables/" + tableId).andExpect(status().isForbidden());
        api.get(kitchen, "/api/v1/rooms").andExpect(status().isForbidden());
        api.post(manager, "/api/v1/rooms/" + room + "/tables", table("")).andExpect(status().isCreated());

        // A waiter can flag a table for cleaning, and it shows on the layout.
        api.patch(waiter, "/api/v1/tables/" + tableId + "/service-status", """
                {"serviceStatus":"TO_CLEAN"}
                """).andExpect(status().isOk());
        api.get(owner, "/api/v1/rooms/" + room + "/layout")
                .andExpect(jsonPath("$.tables[0].liveStatus").value("TO_CLEAN"));
    }

    @Test
    void restaurantsCannotSeeOrTouchEachOthersFloorPlans() throws Exception {
        Actor a = api.signup("A");
        Actor b = api.signup("B");
        String room = createRoom(a, "Main");
        String tableId = ApiClient.read(api.post(a, "/api/v1/rooms/" + room + "/tables", table(""))
                .andExpect(status().isCreated()), "$.id");

        api.get(b, "/api/v1/rooms").andExpect(jsonPath("$.length()").value(0));
        api.get(b, "/api/v1/rooms/" + room + "/layout").andExpect(status().isNotFound());
        api.post(b, "/api/v1/rooms/" + room + "/tables", table("")).andExpect(status().isNotFound());
        api.put(b, "/api/v1/tables/" + tableId, table("")).andExpect(status().isNotFound());
        api.patch(b, "/api/v1/tables/" + tableId + "/service-status", """
                {"serviceStatus":"OUT_OF_SERVICE"}
                """).andExpect(status().isNotFound());
        api.delete(b, "/api/v1/tables/" + tableId).andExpect(status().isNotFound());
        api.delete(b, "/api/v1/rooms/" + room).andExpect(status().isNotFound());

        // A's table is untouched.
        api.get(a, "/api/v1/rooms/" + room + "/layout").andExpect(jsonPath("$.tables.length()").value(1))
                .andExpect(jsonPath("$.tables[0].liveStatus").value("FREE"));
    }
}
