package it.unical.igpe.ristorante.model.kitchen;

/** Portata a cui appartiene una riga della comanda: definisce l'ordine di uscita. */
public enum Course {

    ANTIPASTO("Antipasto", 1),
    PRIMO("Primo", 2),
    SECONDO("Secondo", 3),
    CONTORNO("Contorno", 4),
    DESSERT("Dessert", 5),
    BEVANDA("Bevanda", 0);

    private final String label;
    private final int order;

    Course(String label, int order) {
        this.label = label;
        this.order = order;
    }

    public String getLabel() { return label; }

    public int getOrder() { return order; }

    @Override
    public String toString() { return label; }
}
