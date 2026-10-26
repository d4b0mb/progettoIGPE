package it.ristomanager.cloud.order;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.PostgresTestConfig;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Toppings and options on dishes: who manages them, what an order keeps of them, and the allergens they bring. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class ModifierIT {

    @Autowired
    MockMvc mvc;

    ApiClient api;
    Actor owner;
    Actor manager;
    Actor waiter;
    String table;
    String margherita;
    String cola;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc);
        owner = api.signup("Pizzeria");
        manager = api.staff(owner, "MANAGER");
        waiter = api.staff(owner, "WAITER");
        String room = ApiClient.read(api.post(owner, "/api/v1/rooms", """
                {"name":"Sala","widthMeters":10,"heightMeters":7}
                """).andExpect(status().isCreated()), "$.id");
        table = ApiClient.read(api.post(owner, "/api/v1/rooms/" + room + "/tables", """
                {"xMeters":1,"yMeters":1,"widthMeters":1.2,"heightMeters":1.2,"shape":"SQUARE","seats":4}
                """).andExpect(status().isCreated()), "$.id");
        margherita = dish(owner, "Margherita", "PIZZA", "[\"GLUTEN\",\"MILK\"]");
        cola = dish(owner, "Coca Cola", "DRINK", "[]");
    }

    String dish(Actor who, String name, String course, String allergens) throws Exception {
        return ApiClient.read(api.post(who, "/api/v1/menu/items", """
                {"name":"%s","course":"%s","allergens":%s,"priceCents":700}
                """.formatted(name, course, allergens)).andExpect(status().isCreated()), "$.id");
    }

    String option(Actor who, String name, String course, int cents, String allergens) throws Exception {
        return ApiClient.read(api.post(who, "/api/v1/menu/modifiers", """
                {"name":"%s","course":"%s","section":"Aggiunte","priceCents":%d,"allergens":%s}
                """.formatted(name, course, cents, allergens)).andExpect(status().isCreated()), "$.id");
    }

    String orderWith(String dishId, String note, String... optionIds) {
        String ids = java.util.Arrays.stream(optionIds).map(i -> "\"" + i + "\"").collect(Collectors.joining(","));
        return """
                {"tableId":"%s","items":[{"menuItemId":"%s","quantity":2,"note":"%s","modifierIds":[%s]}]}
                """.formatted(table, dishId, note, ids);
    }

    @Test
    void ownerAndManagerManageOptionsAndEveryoneReadsThem() throws Exception {
        String id = option(owner, "Bufala in cottura", "PIZZA", 300, "[\"MILK\"]");
        option(manager, "Origano", "PIZZA", 0, "[]");

        api.get(waiter, "/api/v1/menu/modifiers").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Bufala in cottura")).andExpect(jsonPath("$[0].priceCents").value(300))
                .andExpect(jsonPath("$[0].allergens[0]").value("MILK")).andExpect(jsonPath("$[1].priceCents").value(0));

        api.post(waiter, "/api/v1/menu/modifiers", "{\"name\":\"X\",\"course\":\"PIZZA\"}").andExpect(status().isForbidden());
        api.put(waiter, "/api/v1/menu/modifiers/" + id, "{\"name\":\"X\",\"course\":\"PIZZA\"}").andExpect(status().isForbidden());
        api.delete(waiter, "/api/v1/menu/modifiers/" + id).andExpect(status().isForbidden());

        api.put(manager, "/api/v1/menu/modifiers/" + id, "{\"name\":\"Bufala in uscita\",\"course\":\"PIZZA\",\"priceCents\":350,\"available\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Bufala in uscita"))
                .andExpect(jsonPath("$.available").value(false));
        api.delete(owner, "/api/v1/menu/modifiers/" + id).andExpect(status().isNoContent());
        api.delete(owner, "/api/v1/menu/modifiers/" + id).andExpect(status().isNotFound());
    }

    @Test
    void invalidOptionsAreRefused() throws Exception {
        api.post(owner, "/api/v1/menu/modifiers", "{\"name\":\"\",\"course\":\"PIZZA\"}").andExpect(status().isBadRequest());
        api.post(owner, "/api/v1/menu/modifiers", "{\"name\":\"X\",\"course\":\"PIZZA\",\"priceCents\":-1}").andExpect(status().isBadRequest());
        api.post(owner, "/api/v1/menu/modifiers", "{\"name\":\"X\"}").andExpect(status().isBadRequest());
        option(owner, "Rucola", "PIZZA", 150, "[]");
        api.post(owner, "/api/v1/menu/modifiers", "{\"name\":\"Rucola\",\"course\":\"PIZZA\"}").andExpect(status().isConflict());
        // The same name is fine on another course.
        api.post(owner, "/api/v1/menu/modifiers", "{\"name\":\"Rucola\",\"course\":\"STARTER\"}").andExpect(status().isCreated());
    }

    @Test
    void anOrderKeepsTheChosenOptionsTheirPriceTheLineNoteAndTheAllergensTheyBring() throws Exception {
        String pistachio = option(owner, "Granella di pistacchio", "PIZZA", 150, "[\"TREE_NUTS\"]");
        String basil = option(owner, "Senza basilico", "PIZZA", 0, "[]");
        // A party with a nut allergy sits at the table.
        api.post(owner, "/api/v1/reservations", """
                {"guestName":"Bianchi","phone":"+39 333 000 3333","startsAt":"%s","partySize":2,"tableId":"%s",
                 "status":"ARRIVED","allergens":["TREE_NUTS"]}
                """.formatted(OffsetDateTime.now().minusMinutes(5), table)).andExpect(status().isCreated());

        api.post(waiter, "/api/v1/orders", orderWith(margherita, "senza pepe", pistachio, basil))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].modifiers.length()").value(2))
                .andExpect(jsonPath("$.items[0].modifiers[0]").value("Granella di pistacchio"))
                .andExpect(jsonPath("$.items[0].modifierCents").value(150))
                .andExpect(jsonPath("$.items[0].note").value("senza pepe"))
                // The margherita alone has no nuts: the topping is what makes the alarm go off.
                .andExpect(jsonPath("$.items[0].allergens[?(@=='TREE_NUTS')]").exists())
                .andExpect(jsonPath("$.items[0].allergens[?(@=='MILK')]").exists())
                .andExpect(jsonPath("$.items[0].conflicts[0]").value("TREE_NUTS"))
                .andExpect(jsonPath("$.hasAllergyConflicts").value(true));
    }

    @Test
    void aPlainOrderIsUnchangedAndAnyDishTakesANote() throws Exception {
        api.post(waiter, "/api/v1/orders", orderWith(cola, "con ghiaccio"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].modifiers.length()").value(0))
                .andExpect(jsonPath("$.items[0].modifierCents").value(0))
                .andExpect(jsonPath("$.items[0].note").value("con ghiaccio"))
                .andExpect(jsonPath("$.hasAllergyConflicts").value(false));
    }

    @Test
    void optionsMustBelongToTheDishesCourseBeAvailableAndBeThisRestaurants() throws Exception {
        String pizzaOption = option(owner, "Rucola", "PIZZA", 150, "[]");
        String off = option(owner, "Fiori di zucca", "PIZZA", 150, "[]");
        api.put(owner, "/api/v1/menu/modifiers/" + off, "{\"name\":\"Fiori di zucca\",\"course\":\"PIZZA\",\"available\":false}").andExpect(status().isOk());

        // A pizza topping on a drink.
        api.post(waiter, "/api/v1/orders", orderWith(cola, "", pizzaOption)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("cannot be added")));
        // Switched off.
        api.post(waiter, "/api/v1/orders", orderWith(margherita, "", off)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("not available")));
        // Unknown.
        api.post(waiter, "/api/v1/orders", orderWith(margherita, "", java.util.UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        // Another restaurant's option.
        Actor other = api.signup("Other");
        String foreign = option(other, "Rucola", "PIZZA", 150, "[]");
        api.post(waiter, "/api/v1/orders", orderWith(margherita, "", foreign)).andExpect(status().isBadRequest());
        api.get(other, "/api/v1/menu/modifiers").andExpect(jsonPath("$.length()").value(1));
        api.delete(other, "/api/v1/menu/modifiers/" + pizzaOption).andExpect(status().isNotFound());
        // Too many on one dish.
        String[] many = IntStream.range(0, 21).mapToObj(i -> pizzaOption).toArray(String[]::new);
        // Repeating one option is the same as picking it once, so this is fine ...
        api.post(waiter, "/api/v1/orders", orderWith(margherita, "", many)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].modifiers.length()").value(1));
        // ... but 21 different ones are refused.
        String[] distinct = IntStream.range(0, 21).mapToObj(i -> java.util.UUID.randomUUID().toString()).toArray(String[]::new);
        api.post(waiter, "/api/v1/orders", orderWith(margherita, "", distinct)).andExpect(status().isBadRequest());
    }
}
