package it.ristomanager.cloud.floor.draft;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A floor plan proposed from a photo. Nothing about it is stored: the owner reviews it and saves the parts they keep
 * through the normal room, table and element endpoints.
 */
public record FloorDraft(
        double widthMeters,
        double heightMeters,
        List<DraftTable> tables,
        List<DraftElement> elements,
        @Schema(description = "Things the owner should know before trusting the draft, as plain sentences") List<String> warnings) {
}
