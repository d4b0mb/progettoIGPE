package it.unical.igpe.ristorante.persistence;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Hashing delle password con BCrypt.
 *
 * Le slide del corso sono esplicite: le password non vanno MAI memorizzate in
 * chiaro. BCrypt genera un sale casuale diverso a ogni chiamata e lo include
 * nella stringa risultante, quindi non serve una colonna separata per il sale
 * e due utenti con la stessa password producono hash diversi.
 */
public final class PasswordHasher {

    /** Costo computazionale: ogni incremento raddoppia il tempo di calcolo. */
    private static final int COST = 10;

    private PasswordHasher() {
        // classe di sole utilità: non deve essere istanziata
    }

    public static String hash(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(COST));
    }

    public static boolean matches(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // hash malformato nel database: si tratta come credenziale errata
            return false;
        }
    }
}
