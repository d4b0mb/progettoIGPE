package it.unical.igpe.ristorante.model.kitchen;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import it.unical.igpe.ristorante.model.Allergen;

/**
 * Una comanda inviata dalla sala alla cucina.
 *
 * È l'oggetto che viaggia sulla rete: implementa Serializable e non contiene
 * riferimenti a componenti grafici o a connessioni, altrimenti non sarebbe
 * serializzabile.
 *
 * Tutti i momenti chiave sono registrati (creazione, presa in carico, pronta,
 * servita): da questi si ricavano i tempi di attesa mostrati in tempo reale.
 */
public class Ticket implements Serializable, Comparable<Ticket> {

    private static final long serialVersionUID = 1L;

    /** Oltre questi minuti in stato aperto, la comanda viene segnalata come in ritardo. */
    public static final int LATE_THRESHOLD_MINUTES = 15;

    private int id;
    private String code = "";
    private int tableNumber;
    private int reservationId;
    private String guestName = "";

    private final List<TicketItem> items = new ArrayList<>();

    private TicketPriority priority = TicketPriority.NORMALE;
    private TicketStatus status = TicketStatus.NUOVA;
    private String note = "";

    /**
     * Allergie e intolleranze dichiarate dal cliente nella prenotazione.
     *
     * Sono un'informazione diversa dagli allergeni contenuti nei piatti:
     * queste dicono che cosa il cliente NON può mangiare, quelli che cosa c'è
     * dentro ogni piatto. L'allarme vero è l'incrocio fra le due, cioè un
     * piatto che contiene proprio ciò a cui il cliente è allergico.
     */
    private Set<Allergen> guestAllergens = EnumSet.noneOf(Allergen.class);

    private String createdBy = "";
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime acknowledgedAt;
    private LocalDateTime readyAt;
    private LocalDateTime servedAt;

    public Ticket() {
    }

    public Ticket(int tableNumber, String createdBy) {
        this.tableNumber = tableNumber;
        this.createdBy = createdBy;
    }

    public void addItem(TicketItem item) {
        if (item != null) {
            items.add(item);
        }
    }

    public void removeItem(TicketItem item) {
        items.remove(item);
    }

    public List<TicketItem> getItems() {
        return items;
    }

    public int getTotalQuantity() {
        int total = 0;
        for (TicketItem i : items) {
            total += i.getQuantity();
        }
        return total;
    }

    /**
     * Unione di tutti gli allergeni presenti nelle righe della comanda.
     * È quello che la cucina deve vedere per primo, in rosso, in cima al biglietto.
     */
    public Set<Allergen> getAllAllergens() {
        Set<Allergen> all = EnumSet.noneOf(Allergen.class);
        for (TicketItem i : items) {
            all.addAll(i.getAllergens());
        }
        return all;
    }

    public boolean hasAllergens() {
        return !getAllAllergens().isEmpty();
    }

    public Set<Allergen> getGuestAllergens() {
        // Un oggetto arrivato da una versione precedente del programma può
        // non avere il campo: si tratta come "nessuna allergia dichiarata".
        if (guestAllergens == null) {
            guestAllergens = EnumSet.noneOf(Allergen.class);
        }
        return guestAllergens;
    }

    public void setGuestAllergens(Set<Allergen> allergens) {
        this.guestAllergens = (allergens == null || allergens.isEmpty())
                ? EnumSet.noneOf(Allergen.class)
                : EnumSet.copyOf(allergens);
    }

    public boolean hasGuestAllergens() {
        return !getGuestAllergens().isEmpty();
    }

    /** Allergeni del piatto che il cliente ha dichiarato di non poter mangiare. */
    public Set<Allergen> conflictsOf(TicketItem item) {
        Set<Allergen> conflicts = EnumSet.noneOf(Allergen.class);
        conflicts.addAll(item.getAllergens());
        conflicts.retainAll(getGuestAllergens());
        return conflicts;
    }

    /** true se almeno un piatto contiene un allergene dichiarato dal cliente. */
    public boolean hasAllergyConflicts() {
        for (TicketItem item : items) {
            if (!conflictsOf(item).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Righe ordinate per portata: è l'ordine in cui la cucina le prepara. */
    public List<TicketItem> getItemsInCourseOrder() {
        List<TicketItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(item -> item.getCourse().getOrder()));
        return sorted;
    }

    /** Minuti trascorsi da quando la comanda è entrata in cucina. */
    public long getElapsedMinutes() {
        LocalDateTime end = (status == TicketStatus.SERVITA && servedAt != null) ? servedAt
                : (status == TicketStatus.PRONTA && readyAt != null) ? readyAt
                : LocalDateTime.now();
        return Duration.between(createdAt, end).toMinutes();
    }

    public String getElapsedText() {
        long minutes = getElapsedMinutes();
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    public boolean isLate() {
        return status.isOpen() && getElapsedMinutes() >= LATE_THRESHOLD_MINUTES;
    }

    /** Passaggio di stato con marcatura temporale automatica. */
    public void advanceTo(TicketStatus newStatus) {
        this.status = newStatus;
        LocalDateTime now = LocalDateTime.now();
        switch (newStatus) {
            case IN_PREPARAZIONE -> acknowledgedAt = now;
            case PRONTA -> readyAt = now;
            case SERVITA -> servedAt = now;
            default -> { /* NUOVA e ANNULLATA non registrano un tempo dedicato */ }
        }
    }

    /**
     * Ordinamento per la coda di cucina: prima la priorità più alta,
     * a parita' di priorità la comanda più vecchia.
     */
    @Override
    public int compareTo(Ticket other) {
        int byPriority = Integer.compare(other.priority.getLevel(), this.priority.getLevel());
        if (byPriority != 0) {
            return byPriority;
        }
        return this.createdAt.compareTo(other.createdAt);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public int getTableNumber() { return tableNumber; }
    public void setTableNumber(int tableNumber) { this.tableNumber = tableNumber; }

    public int getReservationId() { return reservationId; }
    public void setReservationId(int reservationId) { this.reservationId = reservationId; }

    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName == null ? "" : guestName; }

    public TicketPriority getPriority() { return priority; }
    public void setPriority(TicketPriority priority) { this.priority = priority; }

    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null ? "" : note; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    public LocalDateTime getReadyAt() { return readyAt; }
    public void setReadyAt(LocalDateTime readyAt) { this.readyAt = readyAt; }

    public LocalDateTime getServedAt() { return servedAt; }
    public void setServedAt(LocalDateTime servedAt) { this.servedAt = servedAt; }

    @Override
    public String toString() {
        return code + " - Tavolo " + tableNumber;
    }
}
