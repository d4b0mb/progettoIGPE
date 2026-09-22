package it.unical.igpe.ristorante;

import it.unical.igpe.ristorante.net.KitchenServer;

/**
 * Impostazioni scelte all'avvio: quale postazione è questa e dove si trova
 * il server delle comande.
 *
 * Sono valori statici perché riguardano l'intero processo e non un singolo
 * oggetto: cambiare postazione a metà sessione non ha senso, si riavvia.
 */
public final class AppConfig {

    private static String station = "Sala";
    private static String serverHost = "127.0.0.1";
    private static int serverPort = KitchenServer.DEFAULT_PORT;
    private static String databaseFile = "ristorante.db";

    private AppConfig() {
    }

    public static String getStation() { return station; }

    public static void setStation(String value) { station = value; }

    public static String getServerHost() { return serverHost; }

    public static void setServerHost(String value) { serverHost = value; }

    public static int getServerPort() { return serverPort; }

    public static void setServerPort(int value) { serverPort = value; }

    public static String getDatabaseFile() { return databaseFile; }

    public static void setDatabaseFile(String value) { databaseFile = value; }
}
