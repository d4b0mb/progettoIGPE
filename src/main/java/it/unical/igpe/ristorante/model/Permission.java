package it.unical.igpe.ristorante.model;

/**
 * Singolo privilegio che un utente può possedere.
 *
 * Le autorizzazioni non sono legate direttamente al ruolo nel codice della view:
 * la view chiede sempre "questo utente può fare X?" e non "questo utente è
 * un amministratore?". In questo modo aggiungere un nuovo ruolo non richiede
 * di modificare l'interfaccia grafica.
 */
public enum Permission {

    VIEW_RESERVATIONS("Visualizzare le prenotazioni"),
    EDIT_RESERVATIONS("Inserire e modificare le prenotazioni"),
    DELETE_RESERVATIONS("Eliminare le prenotazioni"),
    VIEW_FLOOR_PLAN("Visualizzare la piantina"),
    EDIT_FLOOR_PLAN("Modificare la piantina della sala"),
    SEND_TICKETS("Inviare comande alla cucina"),
    MANAGE_KITCHEN("Gestire lo stato delle comande in cucina"),
    MANAGE_USERS("Gestire gli utenti del sistema");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
