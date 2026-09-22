package it.unical.igpe.ristorante.model.floor;

import java.awt.geom.Area;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * La piantina della sala: dimensioni reali del locale più l'elenco di tutto
 * ciò che vi è collocato.
 *
 * La sala è ridimensionabile (widthMeters / heightMeters): l'utente disegna
 * il proprio locale e poi vi dispone dentro tavoli e strutture.
 */
public class FloorPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String name = "Sala principale";

    private double widthMeters = 12.0;
    private double heightMeters = 9.0;
    /** Passo della griglia di aggancio, in metri. */
    private double gridStepMeters = 0.25;

    private final List<FloorElement> elements = new ArrayList<>();

    public FloorPlan() {
    }

    public FloorPlan(String name, double widthMeters, double heightMeters) {
        this.name = name;
        this.widthMeters = widthMeters;
        this.heightMeters = heightMeters;
    }

    public void add(FloorElement element) {
        if (element == null) {
            return;
        }
        if (element.getId() == 0) {
            element.setId(nextElementId());
        }
        elements.add(element);
    }

    public boolean remove(FloorElement element) {
        return elements.remove(element);
    }

    public void clear() {
        elements.clear();
    }

    /** Lista non modificabile: si passa sempre dai metodi add/remove. */
    public List<FloorElement> getElements() {
        return Collections.unmodifiableList(elements);
    }

    public List<RestaurantTable> getTables() {
        List<RestaurantTable> result = new ArrayList<>();
        for (FloorElement e : elements) {
            if (e instanceof RestaurantTable) {
                result.add((RestaurantTable) e);
            }
        }
        return result;
    }

    public List<Obstacle> getObstacles() {
        List<Obstacle> result = new ArrayList<>();
        for (FloorElement e : elements) {
            if (e instanceof Obstacle) {
                result.add((Obstacle) e);
            }
        }
        return result;
    }

    /**
     * Elemento sotto al punto indicato (coordinate in metri).
     * Si scorre la lista al contrario perché l'ultimo disegnato è quello
     * visivamente sopra, e deve quindi essere il primo selezionabile.
     */
    public FloorElement findAt(double xMeters, double yMeters) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            FloorElement e = elements.get(i);
            if (e.containsPoint(xMeters, yMeters)) {
                return e;
            }
        }
        return null;
    }

    public RestaurantTable findTableById(int tableId) {
        for (RestaurantTable t : getTables()) {
            if (t.getId() == tableId) {
                return t;
            }
        }
        return null;
    }

    public RestaurantTable findTableByNumber(int number) {
        for (RestaurantTable t : getTables()) {
            if (t.getNumber() == number) {
                return t;
            }
        }
        return null;
    }

    /** Porta l'elemento in cima all'ordine di disegno. */
    public void bringToFront(FloorElement element) {
        if (elements.remove(element)) {
            elements.add(element);
        }
    }

    public void sendToBack(FloorElement element) {
        if (elements.remove(element)) {
            elements.add(0, element);
        }
    }

    public int nextTableNumber() {
        int max = 0;
        for (RestaurantTable t : getTables()) {
            max = Math.max(max, t.getNumber());
        }
        return max + 1;
    }

    private int nextElementId() {
        int max = 0;
        for (FloorElement e : elements) {
            max = Math.max(max, e.getId());
        }
        return max + 1;
    }

    /** true se l'elemento sta interamente dentro il perimetro della sala. */
    public boolean isInsideRoom(FloorElement element) {
        java.awt.geom.Rectangle2D room =
                new java.awt.geom.Rectangle2D.Double(0, 0, widthMeters, heightMeters);
        return room.contains(element.getWorldOutline().getBounds2D());
    }

    /**
     * Elementi che si sovrappongono a quello indicato.
     *
     * Si usa java.awt.geom.Area, che sa fare intersezioni tra forme qualsiasi:
     * così il controllo funziona anche con tavoli tondi e muri ruotati, dove
     * un semplice confronto tra rettangoli darebbe falsi positivi.
     */
    public List<FloorElement> findOverlaps(FloorElement element) {
        List<FloorElement> result = new ArrayList<>();
        if (element == null) {
            return result;
        }
        Area a = new Area(element.getWorldOutline());
        for (FloorElement other : elements) {
            if (other == element) {
                continue;
            }
            Area b = new Area(other.getWorldOutline());
            b.intersect(a);
            if (!b.isEmpty()) {
                result.add(other);
            }
        }
        return result;
    }

    public int getTotalSeats() {
        int total = 0;
        for (RestaurantTable t : getTables()) {
            if (t.isReservable()) {
                total += t.getSeats();
            }
        }
        return total;
    }

    public double getRoomAreaSquareMeters() {
        return widthMeters * heightMeters;
    }

    /** Percentuale di superficie occupata da tavoli e strutture. */
    public double getOccupancyRatio() {
        double used = 0;
        for (FloorElement e : elements) {
            used += e.getAreaSquareMeters();
        }
        double room = getRoomAreaSquareMeters();
        return room <= 0 ? 0 : used / room;
    }

    /** Aggancia un valore in metri al passo della griglia. */
    public double snap(double meters) {
        if (gridStepMeters <= 0) {
            return meters;
        }
        return Math.round(meters / gridStepMeters) * gridStepMeters;
    }

    // ------------------------------------------------------------------
    // Istantanee (annulla / ripeti)
    // ------------------------------------------------------------------

    /**
     * Istantanea completa della piantina, come sequenza di byte.
     *
     * Si riusa la serializzazione, la stessa che serve per la rete: scrivere
     * l'oggetto su un ByteArrayOutputStream produce una copia profonda della
     * sala e di tutti i suoi elementi. L'editor conserva queste istantanee
     * per annulla/ripeti, e le confronta per sapere se ci sono modifiche non
     * salvate: due istantanee uguali byte per byte descrivono la stessa sala.
     */
    public byte[] toBytes() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(this);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Istantanea della piantina non riuscita", e);
        }
    }

    public static FloorPlan fromBytes(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (FloorPlan) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Istantanea della piantina non leggibile", e);
        }
    }

    /** Copia profonda e indipendente: modificarla non tocca l'originale. */
    public FloorPlan deepCopy() {
        return fromBytes(toBytes());
    }

    /**
     * Sostituisce il contenuto di questa piantina con quello di un'altra.
     *
     * L'oggetto resta lo stesso: le viste che ne tengono un riferimento
     * continuano a disegnare la piantina giusta, senza doverle avvisare di
     * "cambiare oggetto". Gli elementi vengono presi da {@code other}, che deve
     * quindi essere una copia non più usata altrove (come quelle di fromBytes).
     */
    public void restoreFrom(FloorPlan other) {
        this.id = other.id;
        this.name = other.name;
        this.widthMeters = other.widthMeters;
        this.heightMeters = other.heightMeters;
        this.gridStepMeters = other.gridStepMeters;
        elements.clear();
        elements.addAll(other.elements);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getWidthMeters() { return widthMeters; }
    public void setWidthMeters(double widthMeters) { this.widthMeters = Math.max(2.0, widthMeters); }

    public double getHeightMeters() { return heightMeters; }
    public void setHeightMeters(double heightMeters) { this.heightMeters = Math.max(2.0, heightMeters); }

    public double getGridStepMeters() { return gridStepMeters; }
    public void setGridStepMeters(double gridStepMeters) {
        this.gridStepMeters = Math.max(0.05, gridStepMeters);
    }

    @Override
    public String toString() {
        return name + " (" + widthMeters + " x " + heightMeters + " m)";
    }
}
