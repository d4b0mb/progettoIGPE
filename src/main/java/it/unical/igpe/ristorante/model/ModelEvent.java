package it.unical.igpe.ristorante.model;

/**
 * Notifica emessa dal Model quando il suo stato cambia.
 *
 * Segue lo schema MVC visto a lezione: il Model non conosce la View, si limita
 * a dichiarare "qualcosa è cambiato"; sono le View registrate come listener a
 * decidere se e come aggiornarsi.
 */
public class ModelEvent {

    public enum Type {
        USERS_CHANGED,
        RESERVATIONS_CHANGED,
        FLOOR_PLAN_CHANGED,
        TICKETS_CHANGED,
        CONNECTION_CHANGED
    }

    private final Type type;
    private final Object source;

    public ModelEvent(Type type, Object source) {
        this.type = type;
        this.source = source;
    }

    public Type getType() { return type; }

    public Object getSource() { return source; }

    public boolean is(Type other) { return type == other; }

    @Override
    public String toString() {
        return "ModelEvent[" + type + "]";
    }
}
