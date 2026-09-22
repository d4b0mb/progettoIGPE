package it.unical.igpe.ristorante.persistence;

/**
 * Errore proveniente dal livello di persistenza.
 *
 * Le SQLException sono incapsulate qui perché il resto dell'applicazione non
 * deve sapere che sotto c'è un database: se domani si passasse a dei file, la
 * view e i controller non cambierebbero di una riga.
 *
 * È un'eccezione non controllata: un errore del database non è una condizione
 * che il chiamante possa ragionevolmente risolvere, va segnalata e basta.
 */
public class DataAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataAccessException(String message) {
        super(message);
    }
}
