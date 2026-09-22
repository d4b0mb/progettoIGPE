package it.unical.igpe.ristorante.model.floor;

/** Forme generiche disponibili per i tavoli. */
public enum TableShape {

    ROUND("Tondo"),
    SQUARE("Quadrato"),
    RECTANGLE("Rettangolare");

    private final String label;

    TableShape(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }

    @Override
    public String toString() { return label; }
}
