package it.ristomanager.cloud.floor;

import java.util.UUID;

/** Shapes and states of the floor plan, plus the records the API exchanges. */
public final class FloorTypes {

    private FloorTypes() {
    }

    public enum TableShape { ROUND, SQUARE, RECTANGLE }

    /** Decided by staff. Whether a table is reserved or occupied right now is derived from reservations. */
    public enum ServiceStatus { FREE, TO_CLEAN, OUT_OF_SERVICE }

    /** What the table looks like right now, for the floor plan colours. */
    public enum LiveStatus { FREE, RESERVED, OCCUPIED, TO_CLEAN, OUT_OF_SERVICE }

    public enum ObstacleType { WALL, COLUMN, DOOR, WINDOW, STAIRS, KITCHEN, TOILETS, BAR, ENTRANCE, STORAGE }

    public record Room(UUID id, String name, double widthMeters, double heightMeters, double gridStepMeters) {
    }

    public record DiningTable(
            UUID id, UUID roomId, int number, String name,
            double xMeters, double yMeters, double widthMeters, double heightMeters, double rotationDegrees,
            int seats, TableShape shape, boolean accessible, ServiceStatus serviceStatus) {
    }

    public record Obstacle(
            UUID id, UUID roomId, ObstacleType type, String name,
            double xMeters, double yMeters, double widthMeters, double heightMeters, double rotationDegrees) {
    }

    /** A table together with its status right now. */
    public record TableView(DiningTable table, LiveStatus liveStatus) {
    }

    public record Layout(Room room, java.util.List<TableView> tables, java.util.List<Obstacle> obstacles) {
    }
}
