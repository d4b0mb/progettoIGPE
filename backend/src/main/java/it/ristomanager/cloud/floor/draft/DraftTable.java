package it.ristomanager.cloud.floor.draft;

import io.swagger.v3.oas.annotations.media.Schema;

/** A proposed table. The shape is the name of {@code FloorTypes.TableShape}. */
public record DraftTable(
        String shape,
        @Schema(description = "Centre of the table, in metres from the room's top-left corner") double xMeters,
        @Schema(description = "Centre of the table, in metres from the room's top-left corner") double yMeters,
        double widthMeters,
        double heightMeters,
        @Schema(description = "Clockwise rotation around the centre") double rotationDegrees,
        int seats) {
}
