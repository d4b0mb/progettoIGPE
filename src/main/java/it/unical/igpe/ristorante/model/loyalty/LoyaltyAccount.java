package it.unical.igpe.ristorante.model.loyalty;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Un cliente fedeltà: il codice che l'amministratore gli ha generato e i suoi
 * dati di contatto.
 *
 * Le visite (quando è venuto, quanto ha speso, quanto ha pagato) sono
 * registrate a parte in {@link LoyaltyVisit}, una per una: è da quello storico
 * che in futuro si calcoleranno i programmi fedeltà veri e propri (punti,
 * soglie, sconti). Per ora l'account si limita a raccoglierle.
 */
public class LoyaltyAccount implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    /** Codice fedeltà univoco, es. "FID-00042". Assegnato dal sistema una sola volta. */
    private String code;
    private String guestName;
    private String phone = "";
    private String email = "";
    private String createdBy = "";
    private LocalDateTime createdAt = LocalDateTime.now();

    public LoyaltyAccount() {
    }

    public LoyaltyAccount(String code, String guestName) {
        this.code = code;
        this.guestName = guestName;
    }

    /** Copia indipendente, sullo stesso modello di Reservation.copy(). */
    public LoyaltyAccount copy() {
        LoyaltyAccount c = new LoyaltyAccount();
        c.id = id;
        c.code = code;
        c.guestName = guestName;
        c.phone = phone;
        c.email = email;
        c.createdBy = createdBy;
        c.createdAt = createdAt;
        return c;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone == null ? "" : phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email == null ? "" : email; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return code + " — " + guestName;
    }
}
