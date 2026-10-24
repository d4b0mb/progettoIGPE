package it.ristomanager.cloud.floor;

import it.ristomanager.cloud.floor.FloorTypes.TableShape;

/** Geometry rules for tables, carried over from the desktop RistoManager. */
public final class TableRules {

    /** Recommended edge length per guest, in metres. */
    public static final double SEAT_WIDTH_METERS = 0.60;

    private TableRules() {
    }

    /** How many guests realistically fit around a table of this size and shape (never fewer than 2). */
    public static int suggestedSeats(TableShape shape, double widthMeters, double heightMeters) {
        if (shape == TableShape.ROUND) {
            double circumference = Math.PI * widthMeters;
            return Math.max(2, (int) Math.floor(circumference / SEAT_WIDTH_METERS));
        }
        int alongWidth = (int) Math.floor(widthMeters / SEAT_WIDTH_METERS);
        int alongHeight = (int) Math.floor(heightMeters / SEAT_WIDTH_METERS);
        return Math.max(2, 2 * alongWidth + 2 * alongHeight);
    }

    /** Round and square tables are always as tall as they are wide. */
    public static double normalizedHeight(TableShape shape, double widthMeters, double heightMeters) {
        return shape == TableShape.ROUND || shape == TableShape.SQUARE ? widthMeters : heightMeters;
    }
}
