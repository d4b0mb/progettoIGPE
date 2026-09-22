package it.unical.igpe.ristorante.model.floor;

/**
 * Elemento non prenotabile della sala: muri, colonne, porte, cucina, bagni...
 *
 * Sono tutti rappresentati da un rettangolo ruotabile. Un muro obliquo è
 * semplicemente un rettangolo lungo e sottile con una rotazione: questa scelta
 * mantiene una sola implementazione di trascinamento, ridimensionamento e
 * rotazione per ogni elemento della piantina, invece di un caso speciale per
 * ogni forma.
 */
public class Obstacle extends FloorElement {

    private static final long serialVersionUID = 1L;

    private ObstacleType type = ObstacleType.MURO;

    public Obstacle() {
        super(1.0, 1.0, 2.0, 0.20);
    }

    public Obstacle(ObstacleType type, double x, double y, double w, double h) {
        super(x, y, w, h);
        this.type = type;
    }

    @Override
    public String getKind() {
        return type.getLabel();
    }

    @Override
    public boolean isReservable() {
        return false;
    }

    /** La colonna è disegnata e testata come un cerchio. */
    @Override
    protected boolean hitTestLocal(double px, double py) {
        if (type != ObstacleType.COLONNA) {
            return super.hitTestLocal(px, py);
        }
        double a = getWidthMeters() / 2.0;
        double b = getHeightMeters() / 2.0;
        double dx = (px - getXMeters()) / a;
        double dy = (py - getYMeters()) / b;
        return dx * dx + dy * dy <= 1.0;
    }

    @Override
    public java.awt.Shape getOutline() {
        if (type == ObstacleType.COLONNA) {
            java.awt.geom.Rectangle2D b = getBounds();
            return new java.awt.geom.Ellipse2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
        }
        return getBounds();
    }

    public ObstacleType getType() { return type; }
    public void setType(ObstacleType type) { this.type = type; }

    @Override
    public String toString() {
        return type.getLabel();
    }
}
