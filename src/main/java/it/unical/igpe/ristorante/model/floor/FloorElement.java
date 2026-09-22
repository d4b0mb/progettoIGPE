package it.unical.igpe.ristorante.model.floor;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.Serializable;

/**
 * Classe base astratta di tutto ciò che si può collocare sulla piantina.
 *
 * IMPORTANTE: tutte le coordinate e le dimensioni sono espresse in METRI, mai
 * in pixel. La conversione metri -> pixel avviene una sola volta, nel pannello
 * di disegno, in funzione dello zoom. Questo è ciò che permette di ridimensionare
 * la sala e di zoomare senza che la piantina si deformi, e di mostrare all'utente
 * misure reali (2,40 m x 0,90 m) invece che numeri di pixel privi di significato.
 *
 * (x, y) è il CENTRO dell'elemento: così la rotazione avviene attorno al centro
 * e ridimensionare non fa "scappare" l'oggetto.
 */
public abstract class FloorElement implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String name = "";

    private double xMeters;
    private double yMeters;
    private double widthMeters;
    private double heightMeters;
    private double rotationDegrees;

    protected FloorElement() {
    }

    protected FloorElement(double xMeters, double yMeters, double widthMeters, double heightMeters) {
        this.xMeters = xMeters;
        this.yMeters = yMeters;
        this.widthMeters = widthMeters;
        this.heightMeters = heightMeters;
    }

    /**
     * Etichetta del tipo concreto. È il metodo astratto che ogni sottoclasse
     * deve implementare: l'equivalente Java di una funzione virtuale pura.
     */
    public abstract String getKind();

    /** true solo per gli elementi su cui si possono accettare prenotazioni. */
    public abstract boolean isReservable();

    /**
     * Ingombro non ruotato, in metri. La rotazione viene applicata a parte
     * perché serve un rettangolo "pulito" per le maniglie di ridimensionamento.
     */
    public Rectangle2D.Double getBounds() {
        return new Rectangle2D.Double(
                xMeters - widthMeters / 2.0,
                yMeters - heightMeters / 2.0,
                widthMeters,
                heightMeters);
    }

    /** Trasformazione che porta dal sistema locale dell'elemento a quello della sala. */
    public AffineTransform getTransform() {
        AffineTransform at = new AffineTransform();
        at.translate(xMeters, yMeters);
        at.rotate(Math.toRadians(rotationDegrees));
        at.translate(-xMeters, -yMeters);
        return at;
    }

    /**
     * Test di appartenenza di un punto (in metri), tenendo conto della rotazione.
     * Invece di ruotare la figura, si ruota il punto in senso opposto: è lo
     * stesso risultato ma con un decimo dei calcoli.
     */
    public boolean containsPoint(double px, double py) {
        Point2D p = new Point2D.Double(px, py);
        try {
            getTransform().createInverse().transform(p, p);
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            return false;
        }
        return hitTestLocal(p.getX(), p.getY());
    }

    /**
     * Test di appartenenza nel sistema di riferimento non ruotato.
     * Le sottoclassi con forme non rettangolari (il tavolo tondo) lo ridefiniscono.
     */
    protected boolean hitTestLocal(double px, double py) {
        return getBounds().contains(px, py);
    }

    /**
     * Sagoma dell'elemento nel proprio sistema non ruotato. Le sottoclassi tonde
     * la ridefiniscono con un'ellisse. Serve sia al disegno sia al calcolo delle
     * sovrapposizioni: una sola definizione della forma, usata da entrambi.
     */
    public java.awt.Shape getOutline() {
        return getBounds();
    }

    /** Sagoma nel sistema della sala, rotazione già applicata. */
    public java.awt.Shape getWorldOutline() {
        return getTransform().createTransformedShape(getOutline());
    }

    /** Area in metri quadri, utile per i controlli di densità della sala. */
    public double getAreaSquareMeters() {
        return widthMeters * heightMeters;
    }

    /** Sposta l'elemento di un delta, in metri. */
    public void moveBy(double dx, double dy) {
        this.xMeters += dx;
        this.yMeters += dy;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name; }

    public double getXMeters() { return xMeters; }
    public void setXMeters(double xMeters) { this.xMeters = xMeters; }

    public double getYMeters() { return yMeters; }
    public void setYMeters(double yMeters) { this.yMeters = yMeters; }

    public double getWidthMeters() { return widthMeters; }
    public void setWidthMeters(double widthMeters) { this.widthMeters = Math.max(0.10, widthMeters); }

    public double getHeightMeters() { return heightMeters; }
    public void setHeightMeters(double heightMeters) { this.heightMeters = Math.max(0.10, heightMeters); }

    public double getRotationDegrees() { return rotationDegrees; }
    public void setRotationDegrees(double rotationDegrees) {
        // Normalizza in [0, 360) così il pannello proprietà non mostra mai -450 gradi.
        double r = rotationDegrees % 360.0;
        this.rotationDegrees = (r < 0) ? r + 360.0 : r;
    }
}
