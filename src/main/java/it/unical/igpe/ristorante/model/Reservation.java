package it.unical.igpe.ristorante.model;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Una prenotazione.
 *
 * Contiene tutti i campi richiesti dalle specifiche: nominativo, orario,
 * numero di coperti, seggioloni e spazi passeggino, telefono, email e
 * l'eventuale codice fedeltà.
 *
 * Una prenotazione non conosce l'oggetto Tavolo ma solo il suo id: è il
 * modello (RestaurantModel) a fare da collegamento. Questo evita riferimenti
 * incrociati tra oggetti e rende la serializzazione sulla rete molto più
 * semplice.
 */
public class Reservation implements Serializable, Comparable<Reservation> {

    private static final long serialVersionUID = 1L;

    /** Durata predefinita di un coperto, usata per calcolare le sovrapposizioni. */
    public static final int DEFAULT_DURATION_MINUTES = 120;

    private int id;
    private String guestName;
    private String phone;
    private String email;
    /** Codice fedeltà: null oppure vuoto se il cliente non è registrato. */
    private String loyaltyId;

    private LocalDateTime dateTime;
    private int durationMinutes = DEFAULT_DURATION_MINUTES;

    private int partySize;
    /** Bambini che necessitano di un seggiolone. */
    private int highChairs;
    /** Passeggini da parcheggiare accanto al tavolo. */
    private int strollerSpaces;

    /** Id del tavolo assegnato, 0 se ancora non assegnato. */
    private int tableId;

    private ReservationStatus status = ReservationStatus.ATTESA;
    private Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);
    private String notes = "";

    private String createdBy = "";
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Reservation() {
    }

    public Reservation(String guestName, LocalDateTime dateTime, int partySize) {
        this.guestName = guestName;
        this.dateTime = dateTime;
        this.partySize = partySize;
    }

    /** Orario di fine presunto, cioe' inizio + durata. */
    public LocalDateTime getEndDateTime() {
        if (dateTime == null) {
            return null;
        }
        return dateTime.plusMinutes(durationMinutes);
    }

    /**
     * Due prenotazioni si sovrappongono se i rispettivi intervalli temporali
     * si intersecano. Serve per capire se lo stesso tavolo è già occupato.
     */
    public boolean overlaps(Reservation other) {
        if (other == null || dateTime == null || other.dateTime == null) {
            return false;
        }
        return dateTime.isBefore(other.getEndDateTime())
                && other.dateTime.isBefore(getEndDateTime());
    }

    /** true se il cliente è un cliente fidelizzato. */
    public boolean isLoyaltyMember() {
        return loyaltyId != null && !loyaltyId.isBlank();
    }

    public boolean hasAllergens() {
        return allergens != null && !allergens.isEmpty();
    }

    /**
     * Copia indipendente della prenotazione.
     *
     * Le finestre di modifica lavorano su una copia: se il Model rifiuta il
     * salvataggio e l'utente poi annulla, l'oggetto nell'elenco resta
     * com'era, invece di conservare in memoria dei dati mai salvati.
     */
    public Reservation copy() {
        Reservation c = new Reservation();
        c.id = id;
        c.guestName = guestName;
        c.phone = phone;
        c.email = email;
        c.loyaltyId = loyaltyId;
        c.dateTime = dateTime;
        c.durationMinutes = durationMinutes;
        c.partySize = partySize;
        c.highChairs = highChairs;
        c.strollerSpaces = strollerSpaces;
        c.tableId = tableId;
        c.status = status;
        c.allergens = allergens.isEmpty() ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(allergens);
        c.notes = notes;
        c.createdBy = createdBy;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        return c;
    }

    /** Minuti che mancano all'orario prenotato (negativo se è già passato). */
    public long minutesFromNow() {
        if (dateTime == null) {
            return 0;
        }
        return Duration.between(LocalDateTime.now(), dateTime).toMinutes();
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Ordinamento naturale: per orario crescente. */
    @Override
    public int compareTo(Reservation other) {
        if (dateTime == null || other.dateTime == null) {
            return 0;
        }
        return dateTime.compareTo(other.dateTime);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getLoyaltyId() { return loyaltyId; }
    public void setLoyaltyId(String loyaltyId) { this.loyaltyId = loyaltyId; }

    public LocalDateTime getDateTime() { return dateTime; }
    public void setDateTime(LocalDateTime dateTime) { this.dateTime = dateTime; }

    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }

    public int getPartySize() { return partySize; }
    public void setPartySize(int partySize) { this.partySize = partySize; }

    public int getHighChairs() { return highChairs; }
    public void setHighChairs(int highChairs) { this.highChairs = highChairs; }

    public int getStrollerSpaces() { return strollerSpaces; }
    public void setStrollerSpaces(int strollerSpaces) { this.strollerSpaces = strollerSpaces; }

    public int getTableId() { return tableId; }
    public void setTableId(int tableId) { this.tableId = tableId; }

    public ReservationStatus getStatus() { return status; }
    public void setStatus(ReservationStatus status) { this.status = status; }

    public Set<Allergen> getAllergens() { return allergens; }
    public void setAllergens(Set<Allergen> allergens) {
        this.allergens = (allergens == null) ? EnumSet.noneOf(Allergen.class) : allergens;
    }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return guestName + " (" + partySize + " pax)";
    }
}
