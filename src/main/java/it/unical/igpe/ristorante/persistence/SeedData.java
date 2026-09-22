package it.unical.igpe.ristorante.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;

import it.unical.igpe.ristorante.model.Allergen;
import it.unical.igpe.ristorante.model.Reservation;
import it.unical.igpe.ristorante.model.ReservationStatus;
import it.unical.igpe.ristorante.model.Role;
import it.unical.igpe.ristorante.model.User;
import it.unical.igpe.ristorante.model.floor.FloorPlan;
import it.unical.igpe.ristorante.model.floor.Obstacle;
import it.unical.igpe.ristorante.model.floor.ObstacleType;
import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.model.floor.TableShape;

/**
 * Popolamento iniziale del database.
 *
 * Viene eseguito solo se le tabelle sono vuote, così il primo avvio mostra
 * un locale già arredato e una giornata di prenotazioni realistica, e gli
 * avvii successivi non sovrascrivono il lavoro dell'utente.
 */
public final class SeedData {

    private SeedData() {
    }

    public static void populateIfEmpty(Database db) {
        UserDao userDao = new UserDao(db);
        if (userDao.count() == 0) {
            createUsers(userDao);
        }

        FloorPlanDao planDao = new FloorPlanDao(db);
        FloorPlan plan = planDao.loadFirst();
        if (plan == null) {
            plan = createFloorPlan();
            planDao.save(plan);
        }

        ReservationDao reservationDao = new ReservationDao(db);
        if (reservationDao.count() == 0) {
            createReservations(reservationDao, plan);
        }
    }

    /**
     * Cancella tutto e ricrea i dati dimostrativi.
     *
     * Le prenotazioni di esempio sono create "per oggi" al primo avvio: dopo
     * qualche giorno la giornata corrente risulterebbe vuota, proprio il
     * giorno della presentazione. Questo metodo riporta il database allo
     * stato del primo avvio, con le date di oggi.
     *
     * La cancellazione avviene in una transazione: o si svuotano tutte le
     * tabelle o nessuna.
     */
    public static void resetDemoData(Database db) {
        Connection c = db.getConnection();
        try {
            c.setAutoCommit(false);
            try (Statement stmt = c.createStatement()) {
                stmt.executeUpdate("DELETE FROM ticket_items;");
                stmt.executeUpdate("DELETE FROM tickets;");
                stmt.executeUpdate("DELETE FROM reservations;");
                stmt.executeUpdate("DELETE FROM floor_elements;");
                stmt.executeUpdate("DELETE FROM floor_plans;");
                stmt.executeUpdate("DELETE FROM users;");
            }
            c.commit();
        } catch (SQLException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // l'errore utile da mostrare è quello originale
            }
            throw new DataAccessException("Ripristino dei dati di esempio fallito", e);
        } finally {
            try {
                c.setAutoCommit(true);
            } catch (SQLException ignored) {
                // idem
            }
        }
        populateIfEmpty(db);
    }

    private static void createUsers(UserDao dao) {
        dao.insert(user("admin", "admin123", "Alessandra Ferri", Role.ADMIN));
        dao.insert(user("mrossi", "mario123", "Mario Rossi", Role.OPERATOR));
        dao.insert(user("gbianchi", "giulia123", "Giulia Bianchi", Role.OPERATOR));
        dao.insert(user("stage", "stage123", "Luca Verdi", Role.VIEWER));
    }

    private static User user(String username, String password, String fullName, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(PasswordHasher.hash(password));
        u.setFullName(fullName);
        u.setRole(role);
        u.setActive(true);
        return u;
    }

    /** Una sala di 14 x 10 metri già arredata. */
    private static FloorPlan createFloorPlan() {
        FloorPlan plan = new FloorPlan("Sala principale", 14.0, 10.0);
        plan.setGridStepMeters(0.20);

        // --- Zone funzionali e strutture (non prenotabili) ---
        plan.add(zone(ObstacleType.CUCINA, "Cucina", 2.00, 1.50, 4.00, 3.00));
        plan.add(zone(ObstacleType.BAGNO, "Servizi", 12.60, 1.20, 2.80, 2.40));
        plan.add(zone(ObstacleType.BAR, "Bancone", 7.00, 0.70, 4.20, 1.40));
        plan.add(zone(ObstacleType.INGRESSO, "Ingresso", 7.20, 9.60, 2.00, 0.80));

        // Muretto divisorio in due tratti, con il varco di passaggio al centro
        plan.add(zone(ObstacleType.MURO, "", 5.35, 2.80, 2.10, 0.15));
        plan.add(zone(ObstacleType.MURO, "", 9.85, 2.80, 2.30, 0.15));
        // Un tratto obliquo, per mostrare che la rotazione vale per ogni elemento
        Obstacle diagonal = zone(ObstacleType.MURO, "", 12.40, 5.60, 2.60, 0.15);
        diagonal.setRotationDegrees(45);
        plan.add(diagonal);

        plan.add(zone(ObstacleType.COLONNA, "", 4.60, 5.40, 0.50, 0.50));
        plan.add(zone(ObstacleType.COLONNA, "", 9.60, 5.40, 0.50, 0.50));
        plan.add(zone(ObstacleType.PORTA, "Passa", 4.20, 1.50, 0.18, 1.00));

        // --- Tavoli ---
        plan.add(table(1, 1.60, 4.20, 1.00, 1.00, TableShape.ROUND));
        plan.add(table(2, 4.20, 4.20, 1.00, 1.00, TableShape.ROUND));
        plan.add(table(3, 6.90, 4.20, 1.40, 0.90, TableShape.RECTANGLE));
        plan.add(table(4, 9.50, 4.20, 1.40, 0.90, TableShape.RECTANGLE));
        plan.add(table(5, 12.20, 4.00, 1.00, 1.00, TableShape.ROUND));

        plan.add(table(6, 1.80, 6.80, 1.60, 0.90, TableShape.RECTANGLE));
        plan.add(table(7, 4.40, 6.80, 1.60, 0.90, TableShape.RECTANGLE));
        plan.add(table(8, 7.00, 6.80, 1.60, 0.90, TableShape.RECTANGLE));
        plan.add(table(9, 9.60, 6.80, 1.60, 0.90, TableShape.RECTANGLE));
        plan.add(table(10, 12.40, 6.80, 0.90, 0.90, TableShape.SQUARE));

        plan.add(table(11, 3.20, 8.60, 2.80, 1.00, TableShape.RECTANGLE));
        plan.add(table(12, 10.20, 8.60, 1.60, 1.60, TableShape.ROUND));

        // Il tavolo 11 è quello grande per i gruppi, il 12 è accessibile
        RestaurantTable big = plan.findTableByNumber(11);
        if (big != null) {
            big.setName("Tavolata");
            big.setSeats(10);
        }
        RestaurantTable accessible = plan.findTableByNumber(12);
        if (accessible != null) {
            accessible.setAccessible(true);
            accessible.setSeats(6);
        }
        return plan;
    }

    private static Obstacle zone(ObstacleType type, String name, double x, double y, double w, double h) {
        Obstacle o = new Obstacle(type, x, y, w, h);
        o.setName(name);
        return o;
    }

    private static RestaurantTable table(int number, double x, double y, double w, double h, TableShape shape) {
        return new RestaurantTable(number, x, y, w, h, shape);
    }

    private static void createReservations(ReservationDao dao, FloorPlan plan) {
        LocalDate today = LocalDate.now();

        dao.insert(reservation(plan, "Famiglia Greco", "3401122334", "greco@example.it",
                null, today.atTime(LocalTime.of(12, 30)), 4, 1, 1, 6,
                ReservationStatus.CONFERMATA, EnumSet.of(Allergen.GLUTINE),
                "Bambina di 2 anni, richiesto seggiolone."));

        dao.insert(reservation(plan, "Marco Bevilacqua", "3388765432", "m.bevilacqua@example.it",
                "FID-00218", today.atTime(LocalTime.of(13, 0)), 2, 0, 0, 1,
                ReservationStatus.ARRIVATA, EnumSet.noneOf(Allergen.class),
                "Cliente abituale, tavolo vicino alla finestra."));

        dao.insert(reservation(plan, "Studio Ferraro", "0984123456", "info@studioferraro.it",
                null, today.atTime(LocalTime.of(13, 15)), 9, 0, 0, 11,
                ReservationStatus.CONFERMATA, EnumSet.of(Allergen.CROSTACEI, Allergen.MOLLUSCHI),
                "Pranzo di lavoro, fattura intestata allo studio."));

        dao.insert(reservation(plan, "Chiara Ruffolo", "3491234567", "chiara.r@example.it",
                "FID-00047", today.atTime(LocalTime.of(20, 0)), 2, 0, 0, 2,
                ReservationStatus.CONFERMATA, EnumSet.of(Allergen.LATTE),
                "Intollerante al lattosio, non allergica."));

        dao.insert(reservation(plan, "Compleanno Perri", "3277654321", "perri.fam@example.it",
                null, today.atTime(LocalTime.of(20, 30)), 6, 2, 2, 12,
                ReservationStatus.CONFERMATA, EnumSet.of(Allergen.FRUTTA_A_GUSCIO),
                "Torta portata dai clienti, due seggioloni e spazio passeggini."));

        dao.insert(reservation(plan, "Antonio Curcio", "3356677889", "a.curcio@example.it",
                "FID-00301", today.atTime(LocalTime.of(20, 45)), 4, 0, 0, 8,
                ReservationStatus.ATTESA, EnumSet.noneOf(Allergen.class),
                "Da richiamare per conferma."));

        dao.insert(reservation(plan, "Sofia Mancuso", "3312233445", "sofia.m@example.it",
                null, today.atTime(LocalTime.of(21, 0)), 3, 0, 1, 7,
                ReservationStatus.CONFERMATA, EnumSet.of(Allergen.UOVA, Allergen.SOIA),
                "Passeggino accanto al tavolo."));

        dao.insert(reservation(plan, "Davide Sposato", "3399988776", "d.sposato@example.it",
                null, today.atTime(LocalTime.of(21, 15)), 2, 0, 0, 9,
                ReservationStatus.CONFERMATA, EnumSet.noneOf(Allergen.class), ""));

        dao.insert(reservation(plan, "Gruppo Erasmus", "3467788990", "erasmus.cs@example.it",
                null, today.plusDays(1).atTime(LocalTime.of(20, 30)), 8, 0, 0, 11,
                ReservationStatus.ATTESA, EnumSet.of(Allergen.GLUTINE, Allergen.ARACHIDI),
                "Prenotazione per domani sera."));

        // Una coppia seduta da pochi minuti, a qualunque ora si apra la
        // dimostrazione: c'è sempre un tavolo occupato, con allergie
        // dichiarate, su cui provare subito l'invio di una comanda.
        LocalDateTime justArrived = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(20);
        dao.insert(reservation(plan, "Coppia Esposito", "3471239876", "esposito@example.it",
                null, justArrived, 2, 0, 0, 4,
                ReservationStatus.ARRIVATA, EnumSet.of(Allergen.LATTE, Allergen.GLUTINE),
                "Arrivati senza prenotazione: comanda ancora da inviare."));
    }

    private static Reservation reservation(FloorPlan plan, String name, String phone, String email,
                                           String loyaltyId, LocalDateTime when, int party,
                                           int highChairs, int strollers, int tableNumber,
                                           ReservationStatus status,
                                           java.util.Set<Allergen> allergens, String notes) {
        Reservation r = new Reservation(name, when, party);
        r.setPhone(phone);
        r.setEmail(email);
        r.setLoyaltyId(loyaltyId);
        r.setHighChairs(highChairs);
        r.setStrollerSpaces(strollers);
        r.setStatus(status);
        r.setAllergens(allergens);
        r.setNotes(notes);
        r.setCreatedBy("admin");
        RestaurantTable t = plan.findTableByNumber(tableNumber);
        r.setTableId(t == null ? 0 : t.getId());
        return r;
    }
}
