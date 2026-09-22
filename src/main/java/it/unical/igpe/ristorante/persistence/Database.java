package it.unical.igpe.ristorante.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Gestione della connessione al database SQLite e creazione dello schema.
 *
 * Il file del database viene creato automaticamente da SQLite al primo avvio:
 * non serve installare nulla, il driver JDBC basta a se stesso.
 */
public class Database implements AutoCloseable {

    /** Nome predefinito del file del database, creato nella cartella di lavoro. */
    public static final String DEFAULT_FILE = "ristorante.db";

    private final Connection connection;

    public Database(String fileName) {
        try {
            String url = "jdbc:sqlite:" + fileName;
            this.connection = DriverManager.getConnection(url);
            // I vincoli di chiave esterna in SQLite sono disattivati per default.
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
            }
            createSchema();
            migrate();
        } catch (SQLException e) {
            throw new DataAccessException("Impossibile aprire il database: " + fileName, e);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    private void createSchema() {
        String[] statements = {
            """
            CREATE TABLE IF NOT EXISTS users (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                username      TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                full_name     TEXT,
                role          TEXT NOT NULL,
                active        INTEGER NOT NULL DEFAULT 1,
                created_at    TEXT,
                last_login_at TEXT
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS floor_plans (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                name        TEXT NOT NULL,
                width_m     REAL NOT NULL,
                height_m    REAL NOT NULL,
                grid_step_m REAL NOT NULL
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS floor_elements (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                plan_id       INTEGER NOT NULL,
                element_id    INTEGER NOT NULL,
                kind          TEXT NOT NULL,
                name          TEXT,
                x_m           REAL NOT NULL,
                y_m           REAL NOT NULL,
                w_m           REAL NOT NULL,
                h_m           REAL NOT NULL,
                rotation      REAL NOT NULL DEFAULT 0,
                table_number  INTEGER,
                seats         INTEGER,
                shape         TEXT,
                table_status  TEXT,
                accessible    INTEGER DEFAULT 0,
                obstacle_type TEXT,
                FOREIGN KEY (plan_id) REFERENCES floor_plans(id) ON DELETE CASCADE
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS reservations (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                guest_name      TEXT NOT NULL,
                phone           TEXT,
                email           TEXT,
                loyalty_id      TEXT,
                date_time       TEXT NOT NULL,
                duration_min    INTEGER NOT NULL DEFAULT 120,
                party_size      INTEGER NOT NULL,
                high_chairs     INTEGER NOT NULL DEFAULT 0,
                stroller_spaces INTEGER NOT NULL DEFAULT 0,
                table_id        INTEGER NOT NULL DEFAULT 0,
                status          TEXT NOT NULL,
                allergens       TEXT,
                notes           TEXT,
                created_by      TEXT,
                created_at      TEXT,
                updated_at      TEXT
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS tickets (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                code            TEXT,
                table_number    INTEGER NOT NULL,
                reservation_id  INTEGER DEFAULT 0,
                guest_name      TEXT,
                guest_allergens TEXT,
                priority        TEXT NOT NULL,
                status          TEXT NOT NULL,
                note            TEXT,
                created_by      TEXT,
                created_at      TEXT,
                acknowledged_at TEXT,
                ready_at        TEXT,
                served_at       TEXT
            );
            """,
            """
            CREATE TABLE IF NOT EXISTS ticket_items (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                ticket_id INTEGER NOT NULL,
                name      TEXT NOT NULL,
                quantity  INTEGER NOT NULL DEFAULT 1,
                course    TEXT,
                allergens TEXT,
                note      TEXT,
                FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE
            );
            """,
            // Indici sulle colonne usate nei filtri più frequenti.
            "CREATE INDEX IF NOT EXISTS idx_res_datetime ON reservations(date_time);",
            "CREATE INDEX IF NOT EXISTS idx_res_table ON reservations(table_id);",
            "CREATE INDEX IF NOT EXISTS idx_tickets_status ON tickets(status);"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String sql : statements) {
                stmt.executeUpdate(sql);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Creazione dello schema fallita", e);
        }
    }

    /**
     * Aggiornamenti dello schema per i database creati da versioni precedenti.
     *
     * CREATE TABLE IF NOT EXISTS non tocca una tabella che esiste già: una
     * colonna aggiunta dopo va quindi aggiunta a mano, altrimenti un vecchio
     * ristorante.db farebbe fallire le query che la nominano.
     */
    private void migrate() {
        addColumnIfMissing("tickets", "guest_allergens", "TEXT");
    }

    private void addColumnIfMissing(String table, String column, String type) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ");")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Lettura dello schema fallita", e);
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type + ";");
        } catch (SQLException e) {
            throw new DataAccessException("Aggiornamento dello schema fallito", e);
        }
    }

    /**
     * Versione dei dati secondo SQLite.
     *
     * PRAGMA data_version cambia ogni volta che UN'ALTRA connessione (cioè
     * un'altra postazione, un altro processo) scrive nel database, e resta
     * uguale per le scritture fatte da questa. È il modo più economico per
     * sapere se serve ricaricare: si legge un numero invece di rileggere
     * tutte le tabelle a ogni controllo.
     */
    public long getDataVersion() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA data_version;")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new DataAccessException("Lettura della versione dei dati fallita", e);
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            // in chiusura non c'è nulla di utile da fare se non segnalarlo
            System.err.println("Chiusura del database fallita: " + e.getMessage());
        }
    }
}
