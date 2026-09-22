package it.unical.igpe.ristorante.net;

/**
 * Tipi di messaggio del protocollo sala/cucina.
 *
 * Il protocollo è volutamente minimo: un enum per il tipo e un oggetto di
 * carico utile. Tutto quello che viaggia sul socket è un oggetto Message,
 * quindi chi riceve deve solo guardare il tipo per sapere cosa fare.
 */
public enum MessageType {

    /** Primo messaggio inviato dal client: si presenta con nome e postazione. */
    HELLO,
    /** Il server risponde con l'elenco completo delle comande della giornata. */
    SNAPSHOT,
    /** La sala invia una nuova comanda. */
    TICKET_NEW,
    /** La cucina (o la sala) cambia lo stato di una comanda già esistente. */
    TICKET_UPDATE,
    /** Messaggio di testo libero fra le postazioni. */
    CHAT,
    /** Conferma applicativa. */
    ACK,
    /** Errore segnalato dal server. */
    ERROR
}
