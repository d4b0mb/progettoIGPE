package it.unical.igpe.ristorante.model.floor;

/**
 * Un tavolo prenotabile.
 *
 * È l'unico elemento della piantina su cui si possono assegnare prenotazioni:
 * isReservable() ritorna true solo qui.
 */
public class RestaurantTable extends FloorElement {

    private static final long serialVersionUID = 1L;

    /** Spazio minimo consigliato per coperto lungo il bordo del tavolo, in metri. */
    public static final double SEAT_WIDTH_METERS = 0.60;

    private int number;
    private int seats;
    private TableShape shape = TableShape.RECTANGLE;
    private boolean accessible;

    /**
     * Stato deciso dall'operatore e salvato con la piantina: in servizio
     * (LIBERO), DA_PULIRE oppure FUORI_SERVIZIO.
     */
    private TableStatus serviceStatus = TableStatus.LIBERO;

    /**
     * Stato del momento, ricalcolato dal Model a partire dalle prenotazioni
     * (libero, prenotato, occupato). È transient perché è una conseguenza di
     * altri dati, non un dato: non va salvato, e non deve entrare nelle
     * istantanee di annulla/ripeti, che altrimenti risulterebbero "modificate"
     * da sole con il passare dei minuti.
     */
    private transient TableStatus liveStatus;

    public RestaurantTable() {
        super(1.0, 1.0, 1.20, 0.80);
        this.seats = 4;
    }

    public RestaurantTable(int number, double x, double y, double w, double h, TableShape shape) {
        super(x, y, w, h);
        this.number = number;
        this.shape = shape;
        this.seats = suggestedSeats();
    }

    @Override
    public String getKind() {
        return "Tavolo";
    }

    @Override
    public boolean isReservable() {
        return serviceStatus != TableStatus.FUORI_SERVIZIO;
    }

    /**
     * Quanti coperti stanno realisticamente attorno a questo tavolo, dato
     * l'ingombro. Serve a suggerire un valore quando l'utente ridimensiona:
     * il numero resta comunque modificabile a mano.
     */
    public int suggestedSeats() {
        double w = getWidthMeters();
        double h = getHeightMeters();
        if (shape == TableShape.ROUND) {
            double circumference = Math.PI * w;
            return Math.max(2, (int) Math.floor(circumference / SEAT_WIDTH_METERS));
        }
        int alongWidth = (int) Math.floor(w / SEAT_WIDTH_METERS);
        int alongHeight = (int) Math.floor(h / SEAT_WIDTH_METERS);
        return Math.max(2, 2 * alongWidth + 2 * alongHeight);
    }

    /**
     * Il tavolo tondo ha un test di appartenenza ellittico, non rettangolare:
     * senza questa ridefinizione si potrebbe selezionare un tavolo cliccando
     * nell'angolo vuoto del suo rettangolo di ingombro.
     */
    @Override
    protected boolean hitTestLocal(double px, double py) {
        if (shape != TableShape.ROUND) {
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
        if (shape == TableShape.ROUND) {
            java.awt.geom.Rectangle2D b = getBounds();
            return new java.awt.geom.Ellipse2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
        }
        return getBounds();
    }

    /** Il tavolo tondo mantiene sempre larghezza e altezza uguali. */
    @Override
    public void setWidthMeters(double widthMeters) {
        super.setWidthMeters(widthMeters);
        if (shape == TableShape.ROUND || shape == TableShape.SQUARE) {
            super.setHeightMeters(widthMeters);
        }
    }

    @Override
    public void setHeightMeters(double heightMeters) {
        super.setHeightMeters(heightMeters);
        if (shape == TableShape.ROUND || shape == TableShape.SQUARE) {
            super.setWidthMeters(heightMeters);
        }
    }

    public String getDisplayLabel() {
        return getName() == null || getName().isBlank() ? ("T" + number) : getName();
    }

    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }

    public int getSeats() { return seats; }
    public void setSeats(int seats) { this.seats = Math.max(1, seats); }

    public TableShape getShape() { return shape; }
    public void setShape(TableShape shape) {
        this.shape = shape;
        if (shape == TableShape.ROUND || shape == TableShape.SQUARE) {
            super.setHeightMeters(getWidthMeters());
        }
    }

    /**
     * Lo stato da mostrare: quello fissato dall'operatore, se il tavolo è da
     * pulire o fuori servizio, altrimenti quello calcolato dalle prenotazioni.
     */
    public TableStatus getStatus() {
        if (serviceStatus == TableStatus.DA_PULIRE || serviceStatus == TableStatus.FUORI_SERVIZIO) {
            return serviceStatus;
        }
        return liveStatus == null ? TableStatus.LIBERO : liveStatus;
    }

    public TableStatus getServiceStatus() { return serviceStatus; }

    /**
     * Stato deciso dall'operatore. Solo DA_PULIRE e FUORI_SERVIZIO hanno un
     * significato proprio: qualunque altro valore vuol dire "in servizio".
     */
    public void setServiceStatus(TableStatus status) {
        this.serviceStatus = (status == TableStatus.DA_PULIRE || status == TableStatus.FUORI_SERVIZIO)
                ? status : TableStatus.LIBERO;
    }

    /** Stato calcolato dalle prenotazioni: lo imposta soltanto il Model. */
    public void setLiveStatus(TableStatus status) { this.liveStatus = status; }

    public boolean isAccessible() { return accessible; }
    public void setAccessible(boolean accessible) { this.accessible = accessible; }

    @Override
    public String toString() {
        return "Tavolo " + number + " (" + seats + " posti)";
    }
}
