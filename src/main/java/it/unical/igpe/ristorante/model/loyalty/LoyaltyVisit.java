package it.unical.igpe.ristorante.model.loyalty;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Una visita registrata per un cliente fedeltà: quando è venuto, il totale
 * del conto e quanto ha effettivamente pagato.
 *
 * I due importi sono tenuti separati apposta: uno sconto fedeltà, un buono o
 * un pagamento parziale li fanno divergere, e un programma fedeltà futuro
 * potrebbe aver bisogno di entrambi (quanto vale il cliente, quanto gli è
 * stato scontato).
 */
public class LoyaltyVisit implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private int accountId;
    private LocalDate visitDate;
    private double totalAmount;
    private double paidAmount;
    private String notes = "";
    private String createdBy = "";
    private LocalDateTime createdAt = LocalDateTime.now();

    public LoyaltyVisit() {
    }

    public LoyaltyVisit(int accountId, LocalDate visitDate, double totalAmount, double paidAmount) {
        this.accountId = accountId;
        this.visitDate = visitDate;
        this.totalAmount = totalAmount;
        this.paidAmount = paidAmount;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getAccountId() { return accountId; }
    public void setAccountId(int accountId) { this.accountId = accountId; }

    public LocalDate getVisitDate() { return visitDate; }
    public void setVisitDate(LocalDate visitDate) { this.visitDate = visitDate; }

    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }

    public double getPaidAmount() { return paidAmount; }
    public void setPaidAmount(double paidAmount) { this.paidAmount = paidAmount; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes == null ? "" : notes; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
