package it.unical.igpe.ristorante.model;

/**
 * Errore di validazione dei dati inseriti dall'utente.
 *
 * È un'eccezione controllata (estende Exception e non RuntimeException):
 * il compilatore obbliga chi chiama a gestirla, che è esattamente quello che
 * si vuole per un salvataggio che può legittimamente fallire.
 */
public class ValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String field;

    public ValidationException(String message) {
        this(message, null);
    }

    public ValidationException(String message, String field) {
        super(message);
        this.field = field;
    }

    /** Nome del campo che ha causato l'errore, se noto. */
    public String getField() {
        return field;
    }
}
