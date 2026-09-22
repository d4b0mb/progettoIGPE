package it.unical.igpe.ristorante.view.floor;

import it.unical.igpe.ristorante.model.floor.ObstacleType;
import it.unical.igpe.ristorante.model.floor.TableShape;

/**
 * Strumento attivo nell'editor della sala.
 *
 * Ogni strumento sa già che cosa deve creare: la voce dell'enum porta con se'
 * la forma del tavolo oppure il tipo di ostacolo. Senza questo, il pannello di
 * disegno dovrebbe contenere una lunga catena di if per capire cosa costruire.
 */
public enum ToolMode {

    SELEZIONE("Seleziona", null, null),

    TAVOLO_RETT("Rettangolare", TableShape.RECTANGLE, null),
    TAVOLO_QUAD("Quadrato", TableShape.SQUARE, null),
    TAVOLO_TONDO("Tondo", TableShape.ROUND, null),

    MURO("Muro", null, ObstacleType.MURO),
    COLONNA("Colonna", null, ObstacleType.COLONNA),
    PORTA("Porta", null, ObstacleType.PORTA),
    FINESTRA("Finestra", null, ObstacleType.FINESTRA),
    CUCINA("Cucina", null, ObstacleType.CUCINA),
    BAGNO("Bagni", null, ObstacleType.BAGNO),
    BAR("Bancone", null, ObstacleType.BAR),
    INGRESSO("Ingresso", null, ObstacleType.INGRESSO),
    SCALE("Scale", null, ObstacleType.SCALE),
    DEPOSITO("Deposito", null, ObstacleType.DEPOSITO);

    private final String label;
    private final TableShape tableShape;
    private final ObstacleType obstacleType;

    ToolMode(String label, TableShape tableShape, ObstacleType obstacleType) {
        this.label = label;
        this.tableShape = tableShape;
        this.obstacleType = obstacleType;
    }

    public String getLabel() { return label; }

    public TableShape getTableShape() { return tableShape; }

    public ObstacleType getObstacleType() { return obstacleType; }

    public boolean createsTable() { return tableShape != null; }

    public boolean createsObstacle() { return obstacleType != null; }

    public boolean isDrawing() { return this != SELEZIONE; }

    @Override
    public String toString() { return label; }
}
