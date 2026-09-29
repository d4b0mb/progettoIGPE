package it.unical.igpe.ristorante.model.loyalty;

import java.time.LocalDate;

/**
 * Riepilogo delle visite di un cliente fedeltà: quante sono state, quanto ha
 * speso in totale, quanto ha effettivamente pagato, quando è venuto l'ultima
 * volta.
 *
 * È la base numerica su cui un programma fedeltà futuro potrà decidere soglie
 * e premi (es. "sconto dalla decima visita", "premio oltre i 500 € spesi");
 * per ora si limita a raccogliere questi numeri senza applicarci nessuna regola.
 */
public class LoyaltySummary {

    private final int visitCount;
    private final double totalSpent;
    private final double totalPaid;
    private final LocalDate lastVisit;

    public LoyaltySummary(int visitCount, double totalSpent, double totalPaid, LocalDate lastVisit) {
        this.visitCount = visitCount;
        this.totalSpent = totalSpent;
        this.totalPaid = totalPaid;
        this.lastVisit = lastVisit;
    }

    public int getVisitCount() { return visitCount; }

    public double getTotalSpent() { return totalSpent; }

    public double getTotalPaid() { return totalPaid; }

    public LocalDate getLastVisit() { return lastVisit; }
}
