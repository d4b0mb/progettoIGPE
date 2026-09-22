package it.unical.igpe.ristorante.model.floor;

/** Stato operativo di un tavolo in un dato momento del servizio. */
public enum TableStatus {

    LIBERO("Libero"),
    PRENOTATO("Prenotato"),
    OCCUPATO("Occupato"),
    DA_PULIRE("Da pulire"),
    FUORI_SERVIZIO("Fuori servizio");

    private final String label;

    TableStatus(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }

    @Override
    public String toString() { return label; }
}
