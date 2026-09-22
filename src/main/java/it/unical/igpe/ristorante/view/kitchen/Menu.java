package it.unical.igpe.ristorante.view.kitchen;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import it.unical.igpe.ristorante.model.Allergen;
import it.unical.igpe.ristorante.model.kitchen.Course;
import it.unical.igpe.ristorante.model.kitchen.TicketItem;

/**
 * Il menu del locale.
 *
 * Ogni piatto porta con se' i propri allergeni: questo è il motivo per cui
 * la comanda arriva in cucina già contrassegnata, senza che il cameriere
 * debba ricordarsi che negli spaghetti alle vongole ci sono i molluschi.
 *
 * In un'applicazione destinata alla produzione questi dati starebbero su
 * un'altra tabella del database; qui sono in codice perché il menu non è
 * l'oggetto dell'esercizio e tenerlo fisso rende la dimostrazione ripetibile.
 */
public final class Menu {

    /** Una voce del menu: nome, portata, prezzo e allergeni. */
    public static class Dish {

        private final String name;
        private final Course course;
        private final double price;
        private final Set<Allergen> allergens;

        Dish(String name, Course course, double price, Allergen... allergens) {
            this.name = name;
            this.course = course;
            this.price = price;
            this.allergens = allergens.length == 0
                    ? EnumSet.noneOf(Allergen.class)
                    : EnumSet.copyOf(List.of(allergens));
        }

        public String getName() { return name; }

        public Course getCourse() { return course; }

        public double getPrice() { return price; }

        public Set<Allergen> getAllergens() { return allergens; }

        /** Crea una riga di comanda a partire da questo piatto. */
        public TicketItem toTicketItem(int quantity) {
            TicketItem item = new TicketItem(name, quantity, course);
            item.setAllergens(EnumSet.copyOf(allergens.isEmpty()
                    ? EnumSet.noneOf(Allergen.class) : allergens));
            return item;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Dish> DISHES = new ArrayList<>();

    static {
        // Antipasti
        add("Tagliere di salumi e formaggi", Course.ANTIPASTO, 12.00, Allergen.LATTE);
        add("Bruschette al pomodoro", Course.ANTIPASTO, 6.50, Allergen.GLUTINE);
        add("Alici marinate", Course.ANTIPASTO, 9.00, Allergen.PESCE, Allergen.SOLFITI);
        add("Sformatino di melanzane", Course.ANTIPASTO, 8.50, Allergen.LATTE, Allergen.UOVA);

        // Primi
        add("Spaghetti alle vongole", Course.PRIMO, 14.00,
                Allergen.GLUTINE, Allergen.MOLLUSCHI);
        add("Fileja alla 'nduja", Course.PRIMO, 12.00, Allergen.GLUTINE, Allergen.LATTE);
        add("Risotto ai funghi porcini", Course.PRIMO, 13.00, Allergen.LATTE);
        add("Lasagna al forno", Course.PRIMO, 12.50,
                Allergen.GLUTINE, Allergen.LATTE, Allergen.UOVA, Allergen.SEDANO);
        add("Zuppa di legumi", Course.PRIMO, 10.00, Allergen.SEDANO);

        // Secondi
        add("Tagliata di manzo", Course.SECONDO, 19.00);
        add("Frittura di paranza", Course.SECONDO, 16.00,
                Allergen.GLUTINE, Allergen.PESCE, Allergen.CROSTACEI, Allergen.MOLLUSCHI);
        add("Pollo alle erbe", Course.SECONDO, 14.00);
        add("Parmigiana di melanzane", Course.SECONDO, 12.00, Allergen.LATTE, Allergen.UOVA);
        add("Baccalà in umido", Course.SECONDO, 17.00, Allergen.PESCE, Allergen.SEDANO);

        // Contorni
        add("Patate al forno", Course.CONTORNO, 5.00);
        add("Insalata mista", Course.CONTORNO, 5.00);
        add("Verdure grigliate", Course.CONTORNO, 6.00);

        // Dessert
        add("Tiramisù", Course.DESSERT, 6.00,
                Allergen.GLUTINE, Allergen.LATTE, Allergen.UOVA);
        add("Panna cotta", Course.DESSERT, 5.50, Allergen.LATTE);
        add("Torta di nocciole", Course.DESSERT, 6.50,
                Allergen.GLUTINE, Allergen.LATTE, Allergen.UOVA, Allergen.FRUTTA_A_GUSCIO);
        add("Sorbetto al limone", Course.DESSERT, 4.50);

        // Bevande
        add("Acqua naturale 1L", Course.BEVANDA, 2.50);
        add("Acqua frizzante 1L", Course.BEVANDA, 2.50);
        add("Cirò rosso (calice)", Course.BEVANDA, 5.00, Allergen.SOLFITI);
        add("Birra artigianale", Course.BEVANDA, 6.00, Allergen.GLUTINE);
        add("Caffè", Course.BEVANDA, 1.50);
    }

    private Menu() {
    }

    private static void add(String name, Course course, double price, Allergen... allergens) {
        DISHES.add(new Dish(name, course, price, allergens));
    }

    public static List<Dish> all() {
        return List.copyOf(DISHES);
    }

    public static List<Dish> byCourse(Course course) {
        List<Dish> result = new ArrayList<>();
        for (Dish dish : DISHES) {
            if (dish.getCourse() == course) {
                result.add(dish);
            }
        }
        return result;
    }
}
