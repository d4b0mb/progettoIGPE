package it.ristomanager.cloud.floor.draft;

import io.swagger.v3.oas.annotations.media.Schema;

/** A proposed fixed element (wall, door, bar...). The type is the name of {@code FloorTypes.ObstacleType}. */
public record DraftElement(
        String type,
        @Schema(description = "Centre of the element, in metres from the room's top-left corner") double xMeters,
        @Schema(description = "Centre of the element, in metres from the room's top-left corner") double yMeters,
        double widthMeters,
        double heightMeters,
        @Schema(description = "Clockwise rotation around the centre") double rotationDegrees) {
}
