package it.unical.igpe.ristorante.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import it.unical.igpe.ristorante.model.Allergen;
import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.model.kitchen.Course;
import it.unical.igpe.ristorante.model.kitchen.Ticket;
import it.unical.igpe.ristorante.model.kitchen.TicketItem;
import it.unical.igpe.ristorante.model.kitchen.TicketStatus;
import it.unical.igpe.ristorante.persistence.Database;
import it.unical.igpe.ristorante.persistence.SeedData;
import it.unical.igpe.ristorante.persistence.TicketDao;

/**
 * Sala e cucina che si parlano davvero, attraverso socket veri.
 *
 * Il server ascolta sulla porta 0: il sistema operativo ne sceglie una libera,
 * così i test non entrano in conflitto con un RistoManager in esecuzione.
 * I client aggiornano il Model sull'EDT (invokeLater): i test leggono il
 * Model sull'EDT allo stesso modo, con invokeAndWait.
 */
class KitchenServerTest {

    private Path dbFile;
    private Database serverDb;
    private KitchenServer server;
    private final List<Database> stationDatabases = new ArrayList<>();
    private final List<KitchenClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("ristorante-rete", ".db");
        serverDb = new Database(dbFile.toString());
        SeedData.populateIfEmpty(serverDb);
        server = new KitchenServer(0, serverDb);
        server.setLogger(line -> { });
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (KitchenClient client : clients) {
            client.disconnect();
        }
        server.stop();
        for (Database db : stationDatabases) {
            db.close();
        }
        serverDb.close();
        Files.deleteIfExists(dbFile);
    }

    private RestaurantModel station(String username, String password) {
        Database db = new Database(dbFile.toString());
        stationDatabases.add(db);
        RestaurantModel model = new RestaurantModel(db);
        model.authenticate(username, password);
        return model;
    }

    private KitchenClient connect(RestaurantModel model, String stationName) throws Exception {
        KitchenClient client = new KitchenClient(model);
        client.setServer("127.0.0.1", server.getLocalPort());
        client.setStation(stationName);
        clients.add(client);
        client.connectInBackground();
        waitUntil(client::isConnected, "connessione al server");
        return client;
    }

    private static Ticket ticket(int table, String dish, Allergen... dishAllergens) {
        Ticket t = new Ticket(table, "test");
        TicketItem item = new TicketItem(dish, 1, Course.PRIMO);
        item.setAllergens(dishAllergens.length == 0
                ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(List.of(dishAllergens)));
        t.addItem(item);
        return t;
    }

    @Test
    void aTicketTravelsFromTheHallToTheKitchenAndBack() throws Exception {
        RestaurantModel hall = station("mrossi", "mario123");
        RestaurantModel kitchen = station("gbianchi", "giulia123");
        KitchenClient hallClient = connect(hall, "Sala");
        KitchenClient kitchenClient = connect(kitchen, "Cucina");

        Ticket sent = ticket(7, "Panna cotta", Allergen.LATTE);
        sent.setGuestAllergens(EnumSet.of(Allergen.LATTE));
        assertTrue(hallClient.sendNewTicket(sent));

        waitUntil(() -> onEdt(() -> kitchen.getTickets().size() == 1), "la comanda arriva in cucina");
        Ticket received = onEdtGet(() -> kitchen.getTickets().get(0));
        assertEquals("C-001", received.getCode());
        assertTrue(received.hasAllergyConflicts(), "le allergie del cliente arrivano in cucina");

        received.advanceTo(TicketStatus.PRONTA);
        assertTrue(kitchenClient.sendTicketUpdate(received));
        waitUntil(() -> onEdt(() -> !hall.getTickets().isEmpty()
                && hall.getTickets().get(0).getStatus() == TicketStatus.PRONTA), "lo stato torna in sala");

        Ticket stored = new TicketDao(serverDb).findByDate(LocalDate.now()).get(0);
        assertEquals(TicketStatus.PRONTA, stored.getStatus(), "il server ha salvato l'avanzamento");
        assertEquals(EnumSet.of(Allergen.LATTE), stored.getGuestAllergens());
    }

    @Test
    void concurrentTicketsGetDistinctCodesAndIds() throws Exception {
        int stations = 4;
        int perStation = 15;
        List<KitchenClient> senders = new ArrayList<>();
        for (int i = 0; i < stations; i++) {
            senders.add(connect(station("mrossi", "mario123"), "Sala " + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(stations);
        CountDownLatch go = new CountDownLatch(1);
        for (KitchenClient sender : senders) {
            pool.submit(() -> {
                go.await();
                for (int n = 0; n < perStation; n++) {
                    sender.sendNewTicket(ticket(n + 1, "Acqua"));
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        int expected = stations * perStation;
        waitUntil(() -> server.getTicketsCopy().size() == expected, "tutte le comande arrivano al server");
        Set<String> codes = new HashSet<>();
        Set<Integer> ids = new HashSet<>();
        for (Ticket t : server.getTicketsCopy()) {
            codes.add(t.getCode());
            ids.add(t.getId());
        }
        assertEquals(expected, codes.size(), "nessun codice ripetuto");
        assertEquals(expected, ids.size(), "nessun id ripetuto");
        assertEquals(expected, new TicketDao(serverDb).countToday());
    }

    @Test
    void aSecondServerOnTheSamePortFailsLoudly() {
        KitchenServer second = new KitchenServer(server.getLocalPort(), serverDb);
        assertThrows(IOException.class, second::start);
        assertFalse(second.isRunning());
    }

    @Test
    void theClientReconnectsByItselfWhenTheServerComesBack() throws Exception {
        int port = server.getLocalPort();
        KitchenClient client = connect(station("mrossi", "mario123"), "Sala");
        client.startAutoReconnect();

        server.stop();
        waitUntil(() -> !client.isConnected(), "il client si accorge che il server non c'è più");

        server = new KitchenServer(port, serverDb);
        server.setLogger(line -> { });
        server.start();
        waitUntil(client::isConnected, "il client si ricollega da solo");
    }

    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void waitUntil(Condition condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Tempo scaduto aspettando: " + what);
    }

    private static boolean onEdt(BooleanSupplier supplier) throws Exception {
        boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(() -> result[0] = supplier.getAsBoolean());
        return result[0];
    }

    private static <T> T onEdtGet(Supplier<T> supplier) throws Exception {
        List<T> result = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> result.add(supplier.get()));
        return result.get(0);
    }
}
