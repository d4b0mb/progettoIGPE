package it.unical.igpe.ristorante.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.model.floor.TableStatus;
import it.unical.igpe.ristorante.model.loyalty.LoyaltyAccount;
import it.unical.igpe.ristorante.model.loyalty.LoyaltySummary;
import it.unical.igpe.ristorante.model.loyalty.LoyaltyVisit;
import it.unical.igpe.ristorante.persistence.Database;
import it.unical.igpe.ristorante.persistence.SeedData;

/**
 * Regole di dominio del Model, verificate senza aprire nessuna finestra: è la
 * prova concreta che il Model non dipende dall'interfaccia grafica.
 *
 * Ogni test lavora su un database temporaneo con i dati dimostrativi, creato
 * e cancellato apposta, e usa un giorno lontano nel futuro su cui quei dati
 * non hanno prenotazioni.
 */
class RestaurantModelTest {

    private final LocalDate day = LocalDate.now().plusDays(30);

    private Path dbFile;
    private Database database;
    private RestaurantModel model;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("ristorante-test", ".db");
        database = new Database(dbFile.toString());
        SeedData.populateIfEmpty(database);
        model = new RestaurantModel(database);
        assertNotNull(model.authenticate("admin", "admin123"));
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
        Files.deleteIfExists(dbFile);
    }

    private RestaurantTable table(int number) {
        return model.getFloorPlan().findTableByNumber(number);
    }

    private Reservation reservation(String name, LocalTime time, int party, int tableNumber) {
        Reservation r = new Reservation(name, day.atTime(time), party);
        r.setPhone("333 1234567");
        r.setTableId(tableNumber == 0 ? 0 : table(tableNumber).getId());
        return r;
    }

    private User user(String username) {
        return model.getUsers().stream()
                .filter(u -> u.getUsername().equals(username))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // Prenotazioni
    // ------------------------------------------------------------------

    @Test
    void validReservationIsSavedAndReloaded() throws Exception {
        Reservation r = reservation("Rossi", LocalTime.of(20, 0), 2, 10);
        model.saveReservation(r);
        assertTrue(r.getId() > 0);

        Reservation loaded = new RestaurantModel(database).findReservationById(r.getId());
        assertNotNull(loaded);
        assertEquals("Rossi", loaded.getGuestName());
        assertEquals(table(10).getId(), loaded.getTableId());
        assertEquals("admin", loaded.getCreatedBy());
    }

    @Test
    void missingNameAndShortPhoneAreRejected() {
        Reservation noName = reservation("  ", LocalTime.of(20, 0), 2, 0);
        assertEquals("guestName",
                assertThrows(ValidationException.class, () -> model.saveReservation(noName)).getField());

        Reservation badPhone = reservation("Bianchi", LocalTime.of(20, 0), 2, 0);
        badPhone.setPhone("12-34");
        assertEquals("phone",
                assertThrows(ValidationException.class, () -> model.saveReservation(badPhone)).getField());
    }

    @Test
    void newReservationOnAPastDayIsRejected() {
        Reservation r = new Reservation("Ieri", LocalDate.now().minusDays(1).atTime(20, 0), 2);
        r.setPhone("333 1234567");
        assertEquals("dateTime",
                assertThrows(ValidationException.class, () -> model.saveReservation(r)).getField());
    }

    @Test
    void tooManyGuestsForTheTableIsRejected() {
        Reservation r = reservation("Gruppo", LocalTime.of(20, 0), 5, 10); // T10 ha 4 posti
        assertEquals("tableId",
                assertThrows(ValidationException.class, () -> model.saveReservation(r)).getField());
    }

    @Test
    void overlappingReservationsOnTheSameTableConflict() throws Exception {
        model.saveReservation(reservation("Primo", LocalTime.of(20, 0), 2, 3)); // 20:00-22:00
        Reservation second = reservation("Secondo", LocalTime.of(21, 30), 2, 3);
        assertEquals("tableId",
                assertThrows(ValidationException.class, () -> model.saveReservation(second)).getField());
    }

    @Test
    void backToBackReservationsDoNotConflict() throws Exception {
        model.saveReservation(reservation("Pranzo", LocalTime.of(12, 0), 2, 3)); // fino alle 14:00
        assertDoesNotThrow(() -> model.saveReservation(reservation("Merenda", LocalTime.of(14, 0), 2, 3)));
    }

    @Test
    void cancelledReservationFreesItsTable() throws Exception {
        Reservation first = reservation("Primo", LocalTime.of(20, 0), 2, 3);
        model.saveReservation(first);
        Reservation cancelled = first.copy();
        cancelled.setStatus(ReservationStatus.ANNULLATA);
        model.saveReservation(cancelled);

        assertDoesNotThrow(() -> model.saveReservation(reservation("Secondo", LocalTime.of(20, 30), 2, 3)));
    }

    @Test
    void availableTablesSkipBusyOnesAndStartFromTheSmallest() throws Exception {
        model.saveReservation(reservation("Occupa T10", LocalTime.of(20, 0), 2, 10));

        List<RestaurantTable> available =
                model.findAvailableTables(reservation("Nuovo", LocalTime.of(20, 30), 2, 0));
        assertFalse(available.contains(table(10)), "T10 è occupato in quella fascia");
        assertFalse(available.isEmpty());
        for (int i = 1; i < available.size(); i++) {
            assertTrue(available.get(i - 1).getSeats() <= available.get(i).getSeats());
        }
    }

    @Test
    void outOfServiceTableIsNeitherOfferedNorAccepted() {
        table(10).setServiceStatus(TableStatus.FUORI_SERVIZIO);
        Reservation r = reservation("Nuovo", LocalTime.of(20, 0), 2, 0);
        assertFalse(model.findAvailableTables(r).contains(table(10)));

        r.setTableId(table(10).getId());
        assertEquals("tableId", assertThrows(ValidationException.class, () -> model.saveReservation(r)).getField());
    }

    @Test
    void rejectedEditOfACopyLeavesTheListUntouched() throws Exception {
        Reservation original = reservation("Originale", LocalTime.of(20, 0), 2, 10);
        model.saveReservation(original);

        Reservation edit = model.findReservationById(original.getId()).copy();
        edit.setGuestName("");
        edit.setPartySize(3);
        assertThrows(ValidationException.class, () -> model.saveReservation(edit));

        Reservation inList = model.findReservationById(original.getId());
        assertEquals("Originale", inList.getGuestName());
        assertEquals(2, inList.getPartySize());
    }

    @Test
    void aReservationCanBeCancelledEvenIfItsTableWasRemoved() throws Exception {
        Reservation r = reservation("Tavolo sparito", LocalTime.of(20, 0), 2, 10);
        model.saveReservation(r);
        model.getFloorPlan().remove(table(10));

        Reservation cancel = model.findReservationById(r.getId()).copy();
        cancel.setStatus(ReservationStatus.ANNULLATA);
        assertDoesNotThrow(() -> model.saveReservation(cancel));
    }

    @Test
    void savingThePlanDetachesReservationsOfDeletedTables() throws Exception {
        Reservation r = reservation("Da riassegnare", LocalTime.of(20, 0), 2, 10);
        model.saveReservation(r);
        model.getFloorPlan().remove(table(10));

        assertEquals(1, model.saveFloorPlan());
        assertEquals(0, model.findReservationById(r.getId()).getTableId());
        assertEquals(0, new RestaurantModel(database).findReservationById(r.getId()).getTableId(),
                "anche nel database");
    }

    @Test
    void deletingWorksWithACopy() throws Exception {
        Reservation r = reservation("Da eliminare", LocalTime.of(20, 0), 2, 0);
        model.saveReservation(r);
        model.deleteReservation(r.copy());
        assertNull(model.findReservationById(r.getId()));
    }

    @Test
    void tableStatusIsDerivedFromReservations() throws Exception {
        Reservation seated = reservation("Seduti", LocalTime.of(20, 0), 2, 3);
        seated.setStatus(ReservationStatus.ARRIVATA);
        model.saveReservation(seated);
        model.saveReservation(reservation("Più tardi", LocalTime.of(21, 30), 2, 4));
        table(6).setServiceStatus(TableStatus.DA_PULIRE);

        model.refreshTableStatuses(day.atTime(20, 30));

        assertEquals(TableStatus.OCCUPATO, table(3).getStatus());
        assertEquals(TableStatus.PRENOTATO, table(4).getStatus());
        assertEquals(TableStatus.LIBERO, table(5).getStatus());
        assertEquals(TableStatus.DA_PULIRE, table(6).getStatus(), "lo stato dell'operatore ha la precedenza");
    }

    @Test
    void changesMadeByAnotherStationAreSeenAfterSync() throws Exception {
        assertFalse(model.syncWithDatabase(), "nessuno ha ancora scritto");

        try (Database otherConnection = new Database(dbFile.toString())) {
            RestaurantModel otherStation = new RestaurantModel(otherConnection);
            assertNotNull(otherStation.authenticate("mrossi", "mario123"));
            otherStation.saveReservation(reservation("Dall'altra postazione", LocalTime.of(19, 0), 2, 0));
        }

        assertTrue(model.syncWithDatabase());
        assertTrue(model.getReservations().stream()
                .anyMatch(r -> r.getGuestName().equals("Dall'altra postazione")));
        assertFalse(model.syncWithDatabase(), "già allineato");
    }

    // ------------------------------------------------------------------
    // Permessi e utenti
    // ------------------------------------------------------------------

    @Test
    void permissionsAreEnforcedByTheModelNotJustTheButtons() throws Exception {
        model.logout();
        assertNotNull(model.authenticate("stage", "stage123"));   // sola lettura
        assertThrows(ValidationException.class,
                () -> model.saveReservation(reservation("Vietata", LocalTime.of(20, 0), 2, 0)));

        model.logout();
        assertNotNull(model.authenticate("mrossi", "mario123"));  // operatore
        Reservation r = reservation("Dell'operatore", LocalTime.of(20, 0), 2, 0);
        model.saveReservation(r);
        assertThrows(ValidationException.class, () -> model.deleteReservation(r));
        assertThrows(ValidationException.class, () -> model.saveFloorPlan());
        assertThrows(ValidationException.class, () -> model.saveUser(new User(), "password"));
    }

    @Test
    void wrongPasswordAndDeactivatedUsersCannotLogIn() throws Exception {
        assertNull(model.authenticate("admin", "sbagliata"));

        User stage = user("stage").copy();
        stage.setActive(false);
        model.saveUser(stage, null);
        assertNull(model.authenticate("stage", "stage123"));
    }

    @Test
    void anAdministratorCannotLockThemselvesOut() {
        User deactivated = user("admin").copy();
        deactivated.setActive(false);
        assertThrows(ValidationException.class, () -> model.saveUser(deactivated, null));

        User demoted = user("admin").copy();
        demoted.setRole(Role.OPERATOR);
        assertThrows(ValidationException.class, () -> model.saveUser(demoted, null));

        assertThrows(ValidationException.class, () -> model.deleteUser(user("admin")));
        assertTrue(user("admin").isActive(), "l'elenco non è stato alterato");
    }

    @Test
    void anInactiveAdministratorCanBeDeleted() throws Exception {
        User second = new User();
        second.setUsername("admin2");
        second.setFullName("Secondo amministratore");
        second.setRole(Role.ADMIN);
        second.setActive(false);
        model.saveUser(second, "segreta1");

        assertDoesNotThrow(() -> model.deleteUser(user("admin2")));
    }

    @Test
    void duplicateOrBlankUsernamesAreRejected() {
        User duplicate = new User();
        duplicate.setUsername("mrossi");
        assertEquals("username",
                assertThrows(ValidationException.class, () -> model.saveUser(duplicate, "password")).getField());

        User spaced = new User();
        spaced.setUsername("mario rossi");
        assertEquals("username",
                assertThrows(ValidationException.class, () -> model.saveUser(spaced, "password")).getField());
    }

    // ------------------------------------------------------------------
    // Fedeltà
    // ------------------------------------------------------------------

    @Test
    void generatedLoyaltyCodesAreUniqueAndFollowTheExistingFormat() throws Exception {
        LoyaltyAccount first = model.generateLoyaltyCode("Cliente Uno", "3331112222", "uno@example.it");
        LoyaltyAccount second = model.generateLoyaltyCode("Cliente Due", "3332223333", "");

        assertTrue(first.getCode().matches("FID-\\d{5}"), first.getCode());
        assertTrue(second.getCode().matches("FID-\\d{5}"), second.getCode());
        assertFalse(first.getCode().equals(second.getCode()), "due clienti non condividono lo stesso codice");
        // I dati di esempio arrivano già con FID-00047, FID-00218 e FID-00301:
        // il prossimo codice deve tenerne conto e non ripartire da zero.
        assertTrue(Integer.parseInt(first.getCode().substring(4)) > 301);
        assertNotNull(model.findLoyaltyAccountById(first.getId()));
    }

    @Test
    void onlyAnAdministratorCanManageLoyaltyAccounts() throws Exception {
        model.logout();
        assertNotNull(model.authenticate("stage", "stage123"));   // sola lettura
        assertThrows(ValidationException.class,
                () -> model.generateLoyaltyCode("Vietato", "3330000000", ""));

        model.logout();
        assertNotNull(model.authenticate("mrossi", "mario123"));  // operatore
        assertThrows(ValidationException.class,
                () -> model.generateLoyaltyCode("Vietato", "3330000000", ""));
    }

    @Test
    void blankGuestNameIsRejected() {
        assertEquals("guestName", assertThrows(ValidationException.class,
                () -> model.generateLoyaltyCode("  ", "3330000000", "")).getField());
    }

    @Test
    void visitsAreRecordedAndSummarized() throws Exception {
        LoyaltyAccount account = model.generateLoyaltyCode("Cliente Fedele", "3339998877", "");

        model.addLoyaltyVisit(account.getId(), day.minusDays(10), 50.0, 50.0, "Prima visita");
        model.addLoyaltyVisit(account.getId(), day, 30.0, 25.0, "Sconto fedeltà");

        List<LoyaltyVisit> visits = model.getLoyaltyVisits(account.getId());
        assertEquals(2, visits.size());

        LoyaltySummary summary = model.summarizeLoyaltyAccount(account.getId());
        assertEquals(2, summary.getVisitCount());
        assertEquals(80.0, summary.getTotalSpent(), 0.001);
        assertEquals(75.0, summary.getTotalPaid(), 0.001);
        assertEquals(day, summary.getLastVisit());
    }

    @Test
    void negativeAmountsAreRejected() throws Exception {
        LoyaltyAccount account = model.generateLoyaltyCode("Cliente Fedele", "3339998877", "");
        assertThrows(ValidationException.class,
                () -> model.addLoyaltyVisit(account.getId(), day, -10.0, 0.0, ""));
    }

    @Test
    void deletingAnAccountRemovesItsVisitsToo() throws Exception {
        LoyaltyAccount account = model.generateLoyaltyCode("Cliente Fedele", "3339998877", "");
        model.addLoyaltyVisit(account.getId(), day, 20.0, 20.0, "");

        model.deleteLoyaltyAccount(account);

        assertNull(model.findLoyaltyAccountById(account.getId()));
        assertTrue(model.getLoyaltyVisits(account.getId()).isEmpty());
    }
}
