package it.unical.igpe.ristorante.model.floor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Geometria della piantina, istantanee per annulla/ripeti e stato dei tavoli. */
class FloorPlanTest {

    @Test
    void roundTableIsHitAsACircleNotAsItsBoundingSquare() {
        RestaurantTable t = new RestaurantTable(1, 5, 5, 2, 2, TableShape.ROUND);
        assertTrue(t.containsPoint(5, 5));
        assertTrue(t.containsPoint(5.9, 5));
        assertFalse(t.containsPoint(5.9, 5.9), "l'angolo del quadrato di ingombro è fuori dal tavolo");
    }

    @Test
    void hitTestFollowsTheRotation() {
        Obstacle wall = new Obstacle(ObstacleType.MURO, 5, 5, 4, 0.2);
        assertTrue(wall.containsPoint(6.5, 5));
        wall.setRotationDegrees(90);
        assertFalse(wall.containsPoint(6.5, 5));
        assertTrue(wall.containsPoint(5, 6.5));
    }

    @Test
    void overlapsAreComputedOnTheRealShapes() {
        FloorPlan plan = new FloorPlan("Prova", 10, 10);
        RestaurantTable a = new RestaurantTable(1, 2, 2, 1, 1, TableShape.ROUND);
        // I due quadrati di ingombro si toccano in diagonale, i cerchi no.
        RestaurantTable b = new RestaurantTable(2, 2.9, 2.9, 1, 1, TableShape.ROUND);
        plan.add(a);
        plan.add(b);
        assertTrue(plan.findOverlaps(a).isEmpty());

        b.setXMeters(2.5);
        b.setYMeters(2);
        assertEquals(List.of(b), plan.findOverlaps(a));
    }

    @Test
    void insideRoomUsesTheRealOutline() {
        FloorPlan plan = new FloorPlan("Prova", 10, 10);
        Obstacle wall = new Obstacle(ObstacleType.MURO, 1, 5, 1.8, 0.2);
        plan.add(wall);
        assertTrue(plan.isInsideRoom(wall));
        wall.setXMeters(0.5);
        assertFalse(plan.isInsideRoom(wall));
    }

    @Test
    void snapRoundsToTheNearestGridStep() {
        FloorPlan plan = new FloorPlan("Prova", 10, 10);
        plan.setGridStepMeters(0.25);
        assertEquals(1.25, plan.snap(1.30), 1e-9);
        assertEquals(1.50, plan.snap(1.38), 1e-9);
    }

    @Test
    void suggestedSeatsFollowTheSize() {
        assertEquals(5, new RestaurantTable(1, 0, 0, 1.0, 1.0, TableShape.ROUND).getSeats());
        assertEquals(6, new RestaurantTable(2, 0, 0, 1.4, 0.9, TableShape.RECTANGLE).getSeats());
        assertEquals(4, new RestaurantTable(3, 0, 0, 0.9, 0.9, TableShape.SQUARE).getSeats());
    }

    @Test
    void restoringASnapshotGivesBackAnIndependentCopy() {
        FloorPlan plan = new FloorPlan("Sala", 12, 9);
        RestaurantTable table = new RestaurantTable(1, 2, 2, 1.2, 0.8, TableShape.RECTANGLE);
        plan.add(table);
        byte[] before = plan.toBytes();

        table.setXMeters(7);
        plan.setName("Modificata");
        assertFalse(Arrays.equals(before, plan.toBytes()));

        plan.restoreFrom(FloorPlan.fromBytes(before));
        assertEquals("Sala", plan.getName());
        assertEquals(2, plan.findTableByNumber(1).getXMeters(), 1e-9);
        assertNotSame(table, plan.findTableByNumber(1), "gli elementi ripristinati sono oggetti nuovi");
        assertArrayEquals(before, plan.toBytes());
    }

    @Test
    void theComputedStatusIsNotPartOfTheSnapshot() {
        FloorPlan plan = new FloorPlan("Sala", 12, 9);
        RestaurantTable table = new RestaurantTable(1, 2, 2, 1.2, 0.8, TableShape.RECTANGLE);
        plan.add(table);
        byte[] before = plan.toBytes();

        table.setLiveStatus(TableStatus.OCCUPATO);
        assertArrayEquals(before, plan.toBytes(),
                "il passare del tempo non deve far risultare la piantina modificata");

        table.setServiceStatus(TableStatus.FUORI_SERVIZIO);
        assertFalse(Arrays.equals(before, plan.toBytes()), "la scelta dell'operatore invece sì");
        assertFalse(table.isReservable());
    }

    @Test
    void theOperatorStatusWinsOverTheComputedOne() {
        RestaurantTable table = new RestaurantTable(1, 2, 2, 1.2, 0.8, TableShape.RECTANGLE);
        table.setServiceStatus(TableStatus.OCCUPATO);   // non è una scelta dell'operatore
        assertEquals(TableStatus.LIBERO, table.getServiceStatus());

        table.setLiveStatus(TableStatus.PRENOTATO);
        assertEquals(TableStatus.PRENOTATO, table.getStatus());

        table.setServiceStatus(TableStatus.DA_PULIRE);
        assertEquals(TableStatus.DA_PULIRE, table.getStatus());
        assertTrue(table.isReservable(), "un tavolo da pulire resta prenotabile");
    }
}
