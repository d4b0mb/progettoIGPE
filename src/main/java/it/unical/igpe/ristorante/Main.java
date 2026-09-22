package it.unical.igpe.ristorante;

import java.util.Locale;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import it.unical.igpe.ristorante.model.RestaurantModel;
import it.unical.igpe.ristorante.net.KitchenServer;
import it.unical.igpe.ristorante.persistence.Database;
import it.unical.igpe.ristorante.persistence.SeedData;
import it.unical.igpe.ristorante.view.LauncherFrame;
import it.unical.igpe.ristorante.view.LoginFrame;
import it.unical.igpe.ristorante.view.ServerFrame;
import it.unical.igpe.ristorante.view.common.Theme;

/**
 * Punto di ingresso dell'applicazione.
 *
 * Senza argomenti mostra la schermata di scelta della postazione. Con un
 * argomento avvia direttamente il ruolo indicato, che è comodo per far partire
 * server, sala e cucina con tre comandi separati:
 *
 *   java -jar ristomanager.jar server [porta]
 *   java -jar ristomanager.jar sala   [host] [porta]
 *   java -jar ristomanager.jar cucina [host] [porta]
 *
 * Tutta la costruzione dell'interfaccia passa da SwingUtilities.invokeLater:
 * i componenti Swing devono essere creati e modificati sull'Event Dispatch
 * Thread, non sul thread main.
 */
public class Main {

    /** Evita di aprire una seconda finestra di errore mentre la prima è aperta. */
    private static boolean showingError;

    public static void main(String[] args) {
        // L'applicazione è in italiano: si fissa la lingua invece di affidarsi
        // a quella del sistema, altrimenti i selettori di data e i messaggi
        // standard di Swing comparirebbero in inglese su una macchina inglese.
        Locale.setDefault(Locale.ITALIAN);
        installErrorHandler();

        String mode = (args.length > 0) ? args[0].toLowerCase(Locale.ROOT) : "";
        if ("server".equals(mode)) {
            // "server 8422"; si accetta anche "server host 8422", come per gli altri ruoli.
            if (args.length > 1) {
                parsePort(args[args.length - 1]);
            }
        } else {
            // Indirizzo e porta del server possono essere passati come argomenti
            // successivi: utile per far girare le postazioni su macchine diverse.
            if (args.length > 1) {
                AppConfig.setServerHost(args[1]);
            }
            if (args.length > 2) {
                parsePort(args[2]);
            }
        }

        SwingUtilities.invokeLater(() -> {
            Theme.applySaved();
            switch (mode) {
                case "server" -> startServer();
                case "sala" -> startClient("Sala");
                case "cucina" -> startClient("Cucina");
                default -> new LauncherFrame().setVisible(true);
            }
        });
    }

    private static void parsePort(String text) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            AppConfig.setServerPort(port);
        } catch (NumberFormatException e) {
            System.err.println("Porta non valida: " + text + " (resta " + AppConfig.getServerPort() + ")");
        }
    }

    /** Apre il database e la finestra del server delle comande. */
    public static void startServer() {
        Database database = openDatabase();
        KitchenServer server = new KitchenServer(AppConfig.getServerPort(), database);
        new ServerFrame(server).setVisible(true);
    }

    /** Apre il database e l'accesso per una postazione di sala o di cucina. */
    public static void startClient(String station) {
        AppConfig.setStation(station);
        Database database = openDatabase();
        RestaurantModel model = new RestaurantModel(database);
        new LoginFrame(model).setVisible(true);
    }

    /**
     * Apre (o crea) il database e lo popola al primo avvio.
     *
     * Senza database non c'è niente da mostrare: se l'apertura fallisce (file
     * bloccato, cartella in sola lettura) si spiega il motivo e si esce, invece
     * di lasciare il programma acceso senza nessuna finestra.
     */
    private static Database openDatabase() {
        try {
            Database database = new Database(AppConfig.getDatabaseFile());
            SeedData.populateIfEmpty(database);
            return database;
        } catch (RuntimeException e) {
            e.printStackTrace();
            showError(e);
            System.exit(1);
            throw e;
        }
    }

    /**
     * Ultima rete di sicurezza: un'eccezione non gestita, invece di finire in
     * una console che l'utente non vede (avviando il jar con un doppio clic la
     * console non c'è proprio), viene mostrata in una finestra di errore.
     *
     * Vale anche per l'Event Dispatch Thread: un'eccezione dentro un listener
     * interrompe solo quell'evento, e l'applicazione resta utilizzabile.
     */
    private static void installErrorHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            SwingUtilities.invokeLater(() -> showError(error));
        });
    }

    /** Mostra un errore in modo comprensibile. Va chiamato sull'EDT. */
    public static void showError(Throwable error) {
        if (showingError) {
            return;
        }
        showingError = true;
        try {
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
            if (root != error && root.getMessage() != null) {
                message += "\n\nCausa: " + root.getMessage();
            }
            JOptionPane.showMessageDialog(null, message, "Si è verificato un errore",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            showingError = false;
        }
    }
}
