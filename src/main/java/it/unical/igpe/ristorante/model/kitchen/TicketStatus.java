package it.unical.igpe.ristorante.model.kitchen;

/** Avanzamento della comanda, dalla sala alla cucina e ritorno. */
public enum TicketStatus {

    NUOVA("Nuova"),
    IN_PREPARAZIONE("In preparazione"),
    PRONTA("Pronta"),
    SERVITA("Servita"),
    ANNULLATA("Annullata");

    private final String label;

    TicketStatus(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }

    public boolean isOpen() {
        return this == NUOVA || this == IN_PREPARAZIONE;
    }

    @Override
    public String toString() { return label; }
}
