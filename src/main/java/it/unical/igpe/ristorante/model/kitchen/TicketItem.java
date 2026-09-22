package it.unical.igpe.ristorante.model.kitchen;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Set;

import it.unical.igpe.ristorante.model.Allergen;

/** Una riga della comanda: un piatto, la quantita', le note e gli allergeni. */
public class TicketItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private int quantity = 1;
    private Course course = Course.PRIMO;
    private Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);
    private String note = "";

    public TicketItem() {
    }

    public TicketItem(String name, int quantity, Course course) {
        this.name = name;
        this.quantity = quantity;
        this.course = course;
    }

    public boolean hasAllergens() {
        return allergens != null && !allergens.isEmpty();
    }

    public String getAllergenCodes() {
        if (!hasAllergens()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Allergen a : allergens) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(a.getShortCode());
        }
        return sb.toString();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = Math.max(1, quantity); }

    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }

    public Set<Allergen> getAllergens() { return allergens; }
    public void setAllergens(Set<Allergen> allergens) {
        this.allergens = (allergens == null) ? EnumSet.noneOf(Allergen.class) : allergens;
    }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null ? "" : note; }

    @Override
    public String toString() {
        return quantity + "x " + name;
    }
}
