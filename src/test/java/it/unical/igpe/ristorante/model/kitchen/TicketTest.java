package it.unical.igpe.ristorante.model.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.unical.igpe.ristorante.model.Allergen;

/** Ordinamento della coda di cucina, allergie e passaggi di stato di una comanda. */
class TicketTest {

    private static TicketItem item(String name, Course course, Allergen... allergens) {
        TicketItem item = new TicketItem(name, 1, course);
        item.setAllergens(allergens.length == 0
                ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(List.of(allergens)));
        return item;
    }

    @Test
    void theKitchenQueueShowsHigherPriorityFirstThenTheOldest() {
        Ticket old = new Ticket(1, "sala");
        old.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        Ticket recent = new Ticket(2, "sala");
        recent.setCreatedAt(LocalDateTime.now());
        Ticket urgent = new Ticket(3, "sala");
        urgent.setCreatedAt(LocalDateTime.now());
        urgent.setPriority(TicketPriority.URGENTE);

        List<Ticket> queue = new ArrayList<>(List.of(recent, old, urgent));
        Collections.sort(queue);
        assertEquals(List.of(urgent, old, recent), queue);
    }

    @Test
    void conflictsAreTheDishAllergensTheGuestDeclared() {
        Ticket ticket = new Ticket(4, "sala");
        TicketItem water = item("Acqua naturale", Course.BEVANDA);
        TicketItem pannaCotta = item("Panna cotta", Course.DESSERT, Allergen.LATTE);
        TicketItem lasagna = item("Lasagna", Course.PRIMO, Allergen.GLUTINE, Allergen.LATTE, Allergen.UOVA);
        ticket.addItem(water);
        ticket.addItem(pannaCotta);
        ticket.addItem(lasagna);
        assertFalse(ticket.hasAllergyConflicts(), "nessuna allergia dichiarata, nessun conflitto");

        ticket.setGuestAllergens(EnumSet.of(Allergen.LATTE));
        assertTrue(ticket.conflictsOf(water).isEmpty(),
                "l'acqua non diventa un piatto con latte perché il cliente è allergico al latte");
        assertEquals(EnumSet.of(Allergen.LATTE), ticket.conflictsOf(pannaCotta));
        assertEquals(EnumSet.of(Allergen.LATTE), ticket.conflictsOf(lasagna));
        assertTrue(ticket.hasAllergyConflicts());
        assertEquals(EnumSet.of(Allergen.GLUTINE, Allergen.LATTE, Allergen.UOVA), ticket.getAllAllergens(),
                "gli allergeni contenuti restano quelli dei piatti");
    }

    @Test
    void itemsAreListedInCourseOrder() {
        Ticket ticket = new Ticket(1, "sala");
        TicketItem dessert = item("Tiramisù", Course.DESSERT);
        TicketItem starter = item("Bruschette", Course.ANTIPASTO);
        TicketItem main = item("Tagliata", Course.SECONDO);
        ticket.addItem(dessert);
        ticket.addItem(starter);
        ticket.addItem(main);
        assertEquals(List.of(starter, main, dessert), ticket.getItemsInCourseOrder());
    }

    @Test
    void advancingRecordsTheTimesAndStopsTheLateAlarm() {
        Ticket ticket = new Ticket(1, "sala");
        ticket.setCreatedAt(LocalDateTime.now().minusMinutes(Ticket.LATE_THRESHOLD_MINUTES + 1));
        assertTrue(ticket.isLate());

        ticket.advanceTo(TicketStatus.IN_PREPARAZIONE);
        assertNotNull(ticket.getAcknowledgedAt());
        ticket.advanceTo(TicketStatus.PRONTA);
        assertNotNull(ticket.getReadyAt());
        assertFalse(ticket.isLate(), "una comanda pronta non è più in ritardo");
    }

    @Test
    void guestAllergiesTravelWithTheTicket() throws Exception {
        Ticket ticket = new Ticket(7, "sala");
        ticket.setGuestAllergens(EnumSet.of(Allergen.ARACHIDI));
        ticket.addItem(item("Torta di nocciole", Course.DESSERT, Allergen.FRUTTA_A_GUSCIO));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(ticket);
        }
        Ticket received;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            received = (Ticket) in.readObject();
        }
        assertEquals(EnumSet.of(Allergen.ARACHIDI), received.getGuestAllergens());
        assertEquals(1, received.getItems().size());
    }
}
