package it.ristomanager.cloud.floor.draft;

import java.util.Locale;

/** The words given to the vision model. Kept in one place so they can be tuned without touching any logic. */
final class FloorDraftPrompt {

    static final String SYSTEM = """
            You read photos and sketches of restaurant rooms seen from above (plan view) and describe the layout \
            as JSON. You only answer with JSON that follows the given schema. You never explain, and you never \
            invent things you cannot see.""";

    private FloorDraftPrompt() {
    }

    static String userPrompt(double roomWidthMeters) {
        return String.format(Locale.ROOT, """
                This image is a top-down plan of one restaurant room. The room is about %.1f metres wide. \
                Describe it as JSON.

                Every coordinate is a fraction of the WHOLE IMAGE between 0 and 1: x counts from the left edge, \
                y counts from the top edge. Sizes (width, height) are fractions of the image width and image height.

                "room": the inside rectangle of the room as drawn, that is where the walls' inner faces are. \
                left and right are x values, top and bottom are y values.

                "tables": every dining table. shape is ROUND, SQUARE or RECTANGLE. cx and cy are the centre of the \
                table, width and height its size as seen in the image, rotation the clockwise angle in degrees \
                (0 when the table is aligned with the image edges).

                "elements": fixed parts of the room that are not tables. type is one of:
                - WALL: an interior wall or partition (not the outer boundary of the room)
                - COLUMN: a pillar or post
                - DOOR: a door between rooms or to the outside, not the main entrance
                - WINDOW: a window
                - STAIRS: a staircase
                - KITCHEN: the kitchen or a service area behind the pass
                - TOILETS: the toilets
                - BAR: a bar counter
                - ENTRANCE: the main entrance of the restaurant
                - STORAGE: a storeroom, cupboard or fixed shelving
                Each element has cx, cy, width, height and rotation like a table.

                Ignore chairs, people, plants, text, labels, dimensions, decorations and furniture that is not a \
                dining table. If you are not sure something is a table or a fixed element, leave it out: return an \
                empty list rather than guess.

                "notes": one short sentence about anything unclear in the image, or an empty string.""",
                roomWidthMeters);
    }
}
