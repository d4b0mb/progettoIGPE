package it.unical.igpe.ristorante.model.kitchen;

/**
 * Livello di importanza della comanda.
 *
 * In cucina viene reso con un colore diverso per ogni livello: è l'unica
 * informazione che deve essere leggibile a due metri di distanza, di corsa.
 */
public enum TicketPriority {

    NORMALE("Normale", 0),
    ALTA("Alta", 1),
    URGENTE("Urgente", 2),
    VIP("VIP / Ospite speciale", 3);

    private final String label;
    private final int level;

    TicketPriority(String label, int level) {
        this.label = label;
        this.level = level;
    }

    public String getLabel() { return label; }

    public int getLevel() { return level; }

    @Override
    public String toString() { return label; }
}
