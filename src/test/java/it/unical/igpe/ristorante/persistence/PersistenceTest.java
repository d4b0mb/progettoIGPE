package it.unical.igpe.ristorante.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import it.unical.igpe.ristorante.model.Allergen;
import it.unical.igpe.ristorante.model.ReservationStatus;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.kitchen.Course;
import it.unical.igpe.ristorante.model.kitchen.Ticket;
import it.unical.igpe.ristorante.model.kitchen.TicketItem;

/** Conversioni da e verso SQLite, aggiornamento dello schema e ripristino dei dati. */
class PersistenceTest {

    @Test
    void allergensSurviveTheRoundTrip() {
        Set<Allergen> set = EnumSet.of(Allergen.GLUTINE, Allergen.MOLLUSCHI);
        assertEquals(set, Converters.toAllergens(Converters.toText(set)));
        assertEquals("", Converters.toText(EnumSet.noneOf(Allergen.class)));
        assertTrue(Converters.toAllergens(null).isEmpty());
    }

    @Test
    void unknownValuesFallBackInsteadOfBreakingTheLoad() {
        assertEquals(EnumSet.of(Allergen.UOVA), Converters.toAllergens("UOVA, NON_ESISTE"));
        assertEquals(ReservationStatus.ATTESA,
                Converters.toEnum(ReservationStatus.class, "BOH", ReservationStatus.ATTESA));
        assertNull(Converters.toDateTime("non è una data"));
    }

    @Test
    void datesSurviveTheRoundTrip() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 11, 20, 30);
        assertEquals(t, Converters.toDateTime(Converters.toText(t)));
    }

    @Test
    void aDatabaseFromThePreviousVersionGetsTheNewColumn() throws Exception {
        Path file = Files.createTempFile("ristorante-vecchio", ".db");
        try {
            // La tabella tickets come la creava la versione precedente: senza guest_allergens.
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
                 Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE tickets (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT, "
                        + "table_number INTEGER NOT NULL, reservation_id INTEGER DEFAULT 0, guest_name TEXT, "
                        + "priority TEXT NOT NULL, status TEXT NOT NULL, note TEXT, created_by TEXT, "
                        + "created_at TEXT, acknowledged_at TEXT, ready_at TEXT, served_at TEXT);");
            }
            try (Database db = new Database(file.toString())) {
                Ticket ticket = new Ticket(3, "test");
                ticket.setCode("C-001");
                ticket.setGuestAllergens(EnumSet.of(Allergen.SESAMO));
                ticket.addItem(new TicketItem("Acqua naturale 1L", 1, Course.BEVANDA));
                new TicketDao(db).insert(ticket);

                List<Ticket> loaded = new TicketDao(db).findByDate(LocalDate.now());
                assertEquals(1, loaded.size());
                assertEquals(EnumSet.of(Allergen.SESAMO), loaded.get(0).getGuestAllergens());
                assertEquals(1, loaded.get(0).getItems().size());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void theDemoResetRecreatesTodaysData() throws Exception {
        Path file = Files.createTempFile("ristorante-reset", ".db");
        try (Database db = new Database(file.toString())) {
            SeedData.populateIfEmpty(db);
            RestaurantModel before = new RestaurantModel(db);
            before.authenticate("admin", "admin123");
            before.getFloorPlan().clear();
            before.saveFloorPlan();

            SeedData.resetDemoData(db);

            RestaurantModel after = new RestaurantModel(db);
            assertEquals(4, after.getUsers().size());
            assertEquals(12, after.getFloorPlan().getTables().size());
            assertFalse(after.getReservationsForDate(LocalDate.now()).isEmpty());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
