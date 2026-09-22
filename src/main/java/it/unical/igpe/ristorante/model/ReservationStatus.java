package it.unical.igpe.ristorante.model;

/** Ciclo di vita di una prenotazione. */
public enum ReservationStatus {

    ATTESA("In attesa"),
    CONFERMATA("Confermata"),
    ARRIVATA("Cliente arrivato"),
    COMPLETATA("Completata"),
    ANNULLATA("Annullata"),
    NO_SHOW("Non presentato");

    private final String label;

    ReservationStatus(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }

    /** Una prenotazione "viva" occupa il tavolo; le altre no. */
    public boolean occupiesTable() {
        return this == ATTESA || this == CONFERMATA || this == ARRIVATA;
    }

    @Override
    public String toString() { return label; }
}
