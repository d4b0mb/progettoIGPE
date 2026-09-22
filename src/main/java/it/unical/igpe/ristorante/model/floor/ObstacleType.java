package it.unical.igpe.ristorante.model.floor;

/**
 * Tutto ciò che occupa spazio in sala ma non è prenotabile.
 *
 * Il flag "structural" distingue gli elementi che delimitano lo spazio
 * (muri, colonne, porte) dalle zone funzionali (cucina, bagni, bar), che
 * vengono disegnate in modo diverso sulla piantina.
 */
public enum ObstacleType {

    MURO("Muro", true),
    COLONNA("Colonna", true),
    PORTA("Porta", true),
    FINESTRA("Finestra", true),
    SCALE("Scale", true),
    CUCINA("Cucina", false),
    BAGNO("Bagni", false),
    BAR("Bancone bar", false),
    INGRESSO("Ingresso", false),
    DEPOSITO("Deposito", false);

    private final String label;
    private final boolean structural;

    ObstacleType(String label, boolean structural) {
        this.label = label;
        this.structural = structural;
    }

    public String getLabel() { return label; }

    public boolean isStructural() { return structural; }

    @Override
    public String toString() { return label; }
}
