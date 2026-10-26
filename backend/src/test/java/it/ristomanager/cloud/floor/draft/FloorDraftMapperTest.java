package it.ristomanager.cloud.floor.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import it.ristomanager.cloud.floor.FloorTypes.TableShape;
import it.ristomanager.cloud.floor.TableRules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The test image is 400 x 300 px and the room spans 0.1..0.9 of it on both axes, typed as 16 m wide. So the room is
 * 320 px wide (0.05 m per pixel), 12 m high, one unit of x is 20 m and one unit of y is 15 m.
 */
class FloorDraftMapperTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int IMAGE_WIDTH = 400;
    private static final int IMAGE_HEIGHT = 300;
    private static final double ROOM_WIDTH = 16;
    private static final double EPS = 1e-9;

    @Test
    void scalesAFractionOfTheImageToMetresOnANonSquareImage() {
        ObjectNode answer = answer();
        tables(answer).add(table("RECTANGLE", 0.5, 0.5, 0.1, 0.1, 0));

        FloorDraft draft = map(answer);

        assertThat(draft.widthMeters()).isEqualTo(16.0);
        assertThat(draft.heightMeters()).isEqualTo(12.0);
        assertThat(draft.tables()).hasSize(1);
        DraftTable table = draft.tables().get(0);
        assertThat(table.shape()).isEqualTo("RECTANGLE");
        assertThat(table.xMeters()).isCloseTo(8.0, within(EPS));
        assertThat(table.yMeters()).isCloseTo(6.0, within(EPS));
        assertThat(table.widthMeters()).isCloseTo(2.0, within(EPS));
        assertThat(table.heightMeters()).isCloseTo(1.5, within(EPS));
        assertThat(table.seats()).isEqualTo(TableRules.suggestedSeats(TableShape.RECTANGLE, 2.0, 1.5));
        assertThat(draft.warnings()).isEmpty();
    }

    @Test
    void positionsAndSizesSnapToTheGrid() {
        ObjectNode answer = answer();
        // Raw metres: x 8.03, y 6.02, width 2.23, height 1.52.
        tables(answer).add(table("RECTANGLE", 0.5015, 0.1 + 6.02 / 15, 2.23 / 20, 1.52 / 15, 0));

        DraftTable table = map(answer).tables().get(0);

        assertThat(table.xMeters()).isCloseTo(8.05, within(EPS));
        assertThat(table.yMeters()).isCloseTo(6.0, within(EPS));
        assertThat(table.widthMeters()).isCloseTo(2.25, within(EPS));
        assertThat(table.heightMeters()).isCloseTo(1.5, within(EPS));
    }

    @Test
    void rotationIsNormalisedAndSnappedToFifteenDegrees() {
        double[] given = {7, 8, 100, -20, 370, 360};
        double[] expected = {0, 15, 105, 345, 15, 0};
        ObjectNode answer = answer();
        for (int i = 0; i < given.length; i++) {
            tables(answer).add(table("RECTANGLE", 0.15 + 0.12 * i, 0.5, 0.1, 0.1, given[i]));
        }

        FloorDraft draft = map(answer);

        assertThat(draft.tables()).hasSize(given.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(draft.tables().get(i).rotationDegrees()).isEqualTo(expected[i]);
        }
    }

    @Test
    void squareAndRoundTablesAreAsTallAsTheyAreWide() {
        ObjectNode answer = answer();
        tables(answer).add(table("SQUARE", 0.3, 0.5, 0.1, 0.2, 0));
        tables(answer).add(table("ROUND", 0.7, 0.5, 0.05, 0.15, 0));

        FloorDraft draft = map(answer);

        DraftTable square = draft.tables().get(0);
        assertThat(square.widthMeters()).isCloseTo(2.0, within(EPS));
        assertThat(square.heightMeters()).isCloseTo(2.0, within(EPS));
        assertThat(square.seats()).isEqualTo(TableRules.suggestedSeats(TableShape.SQUARE, 2.0, 2.0));
        DraftTable round = draft.tables().get(1);
        assertThat(round.widthMeters()).isCloseTo(1.0, within(EPS));
        assertThat(round.heightMeters()).isCloseTo(1.0, within(EPS));
        assertThat(round.seats()).isEqualTo(TableRules.suggestedSeats(TableShape.ROUND, 1.0, 1.0));
    }

    @Test
    void itemsWithTheirCentreOutsideTheRoomAreDroppedAndOthersClampedInside() {
        ObjectNode answer = answer();
        tables(answer).add(table("SQUARE", 0.95, 0.5, 0.05, 0.05, 0));  // x = 17 m, room is 16 m wide
        tables(answer).add(table("SQUARE", 0.0, 0.5, 0.05, 0.05, 0));   // x = -2 m
        tables(answer).add(table("SQUARE", 0.1 + 16.05 / 20, 0.5, 0.05, 0.05, 0)); // 0.05 m past the wall: kept, clamped

        FloorDraft draft = map(answer);

        assertThat(draft.tables()).hasSize(1);
        assertThat(draft.tables().get(0).xMeters()).isCloseTo(16.0, within(EPS));
        assertThat(draft.warnings()).containsExactly("Ignored 2 items outside the room");
    }

    @Test
    void tinyAndHugeTablesAreDropped() {
        ObjectNode answer = answer();
        tables(answer).add(table("SQUARE", 0.3, 0.5, 0.01, 0.01, 0));      // 0.2 m
        tables(answer).add(table("RECTANGLE", 0.5, 0.5, 0.4, 0.1, 0));     // 8 m wide
        tables(answer).add(table("RECTANGLE", 0.7, 0.5, 0.1, 0.1, 0));     // fine

        FloorDraft draft = map(answer);

        assertThat(draft.tables()).hasSize(1);
        assertThat(draft.warnings()).containsExactly("Ignored 2 items with an implausible size");
    }

    @Test
    void aTableOnTopOfAnEarlierOneIsADuplicate() {
        ObjectNode answer = answer();
        tables(answer).add(table("RECTANGLE", 0.5, 0.5, 0.1, 0.1, 0));
        tables(answer).add(table("RECTANGLE", 0.51, 0.5, 0.1, 0.1, 0));   // 0.2 m to the side: 90% overlap
        tables(answer).add(table("RECTANGLE", 0.6, 0.5, 0.1, 0.1, 0));    // next to it, touching only

        FloorDraft draft = map(answer);

        assertThat(draft.tables()).hasSize(2);
        assertThat(draft.tables().get(0).xMeters()).isCloseTo(8.0, within(EPS));
        assertThat(draft.tables().get(1).xMeters()).isCloseTo(10.0, within(EPS));
        assertThat(draft.warnings()).containsExactly("Ignored 1 duplicate table");
    }

    @Test
    void tablesAreCappedAtEighty() {
        ObjectNode answer = answer();
        for (int column = 0; column < 10; column++) {
            for (int row = 0; row < 9; row++) {
                tables(answer).add(table("SQUARE", 0.1 + (1 + column) / 20.0, 0.1 + (1 + row) / 15.0, 0.025, 0.0333, 0));
            }
        }

        FloorDraft draft = map(answer);

        assertThat(draft.tables()).hasSize(80);
        assertThat(draft.warnings()).containsExactly("Ignored 10 tables beyond the limit of 80");
    }

    @Test
    void elementsAreCappedAtForty() {
        ObjectNode answer = answer();
        tables(answer).add(table("SQUARE", 0.5, 0.5, 0.1, 0.1, 0));
        for (int i = 0; i < 50; i++) {
            elements(answer).add(element("COLUMN", 0.1 + (1 + i % 10) / 20.0, 0.1 + (1 + i / 10) / 15.0, 0.025, 0.0333, 0));
        }

        FloorDraft draft = map(answer);

        assertThat(draft.elements()).hasSize(40);
        assertThat(draft.warnings()).containsExactly("Ignored 10 elements beyond the limit of 40");
    }

    @Test
    void aTableWithAnUnknownShapeIsSkipped() {
        ObjectNode answer = answer();
        tables(answer).add(table("TRIANGLE", 0.3, 0.5, 0.1, 0.1, 0));
        tables(answer).add(table("ROUND", 0.7, 0.5, 0.1, 0.1, 0));

        FloorDraft draft = map(answer);

        assertThat(draft.tables()).extracting(DraftTable::shape).containsExactly("ROUND");
        assertThat(draft.warnings()).containsExactly("Ignored 1 item of an unknown kind");
    }

    @Test
    void anElementWithAnUnknownTypeIsSkipped() {
        ObjectNode answer = answer();
        tables(answer).add(table("ROUND", 0.7, 0.5, 0.1, 0.1, 0));
        elements(answer).add(element("FOUNTAIN", 0.3, 0.5, 0.1, 0.1, 0));
        elements(answer).add(element("BAR", 0.3, 0.3, 0.1, 0.1, 0));

        FloorDraft draft = map(answer);

        assertThat(draft.elements()).extracting(DraftElement::type).containsExactly("BAR");
        assertThat(draft.warnings()).containsExactly("Ignored 1 item of an unknown kind");
    }

    @Test
    void elementsAreScaledClampedAndFilteredBySize() {
        ObjectNode answer = answer();
        tables(answer).add(table("ROUND", 0.7, 0.5, 0.1, 0.1, 0));
        elements(answer).add(element("DOOR", 0.5, 0.1, 0.05, 0.0067, 0));     // 1 m x 0.1 m on the top wall
        elements(answer).add(element("COLUMN", 0.3, 0.5, 0.001, 0.001, 0));   // 0.02 m: too small
        elements(answer).add(element("WALL", 0.5, 0.5, 1.0, 0.05, 0));        // 20 m: wider than the room

        FloorDraft draft = map(answer);

        assertThat(draft.elements()).hasSize(1);
        DraftElement door = draft.elements().get(0);
        assertThat(door.type()).isEqualTo("DOOR");
        assertThat(door.xMeters()).isCloseTo(8.0, within(EPS));
        assertThat(door.yMeters()).isCloseTo(0.0, within(EPS));
        assertThat(door.widthMeters()).isCloseTo(1.0, within(EPS));
        assertThat(door.heightMeters()).isCloseTo(0.1, within(EPS));
        assertThat(draft.warnings()).containsExactly("Ignored 2 items with an implausible size");
    }

    @Test
    void aMissingRoomIsNotFound() {
        ObjectNode answer = answer();
        answer.remove("room");

        assertRoomNotFound(answer);
    }

    @Test
    void anInvertedRoomIsNotFound() {
        ObjectNode answer = answer();
        room(answer, 0.9, 0.1, 0.1, 0.9);

        assertRoomNotFound(answer);
    }

    @Test
    void aRoomNarrowerThanFiftyPixelsIsNotFound() {
        ObjectNode answer = answer();
        room(answer, 0.1, 0.1, 0.2, 0.9);   // 40 px wide

        assertRoomNotFound(answer);
    }

    @Test
    void aRoomUnderOneMetreHighIsNotFound() {
        ObjectNode answer = answer();
        room(answer, 0.1, 0.5, 0.9, 0.51);   // 3 px high = 0.15 m

        assertRoomNotFound(answer);
    }

    @Test
    void noTablesGivesAWarning() {
        FloorDraft draft = map(answer());

        assertThat(draft.tables()).isEmpty();
        assertThat(draft.warnings()).containsExactly("No tables were found in the photo");
    }

    @Test
    void theModelNoteIsCopiedAsAWarning() {
        ObjectNode answer = answer();
        tables(answer).add(table("ROUND", 0.7, 0.5, 0.1, 0.1, 0));
        answer.put("notes", "  The photo is blurry  ");

        assertThat(map(answer).warnings()).containsExactly("Model note: The photo is blurry");
    }

    @Test
    void aBlankModelNoteIsNotCopied() {
        ObjectNode answer = answer();
        tables(answer).add(table("ROUND", 0.7, 0.5, 0.1, 0.1, 0));
        answer.put("notes", "   ");

        assertThat(map(answer).warnings()).isEmpty();
    }

    @Test
    void aLongModelNoteIsTruncatedToTwoHundredCharacters() {
        ObjectNode answer = answer();
        tables(answer).add(table("ROUND", 0.7, 0.5, 0.1, 0.1, 0));
        answer.put("notes", "x".repeat(300));

        assertThat(map(answer).warnings()).containsExactly("Model note: " + "x".repeat(200));
    }

    private static void assertRoomNotFound(ObjectNode answer) {
        assertThatThrownBy(() -> map(answer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("room not found");
    }

    private static FloorDraft map(ObjectNode answer) {
        return FloorDraftMapper.toDraft(answer, IMAGE_WIDTH, IMAGE_HEIGHT, ROOM_WIDTH);
    }

    /** A valid answer with the room at 0.1..0.9 and nothing in it. */
    private static ObjectNode answer() {
        ObjectNode answer = JSON.createObjectNode();
        room(answer, 0.1, 0.1, 0.9, 0.9);
        answer.putArray("tables");
        answer.putArray("elements");
        answer.put("notes", "");
        return answer;
    }

    private static void room(ObjectNode answer, double left, double top, double right, double bottom) {
        answer.putObject("room").put("left", left).put("top", top).put("right", right).put("bottom", bottom);
    }

    private static ArrayNode tables(ObjectNode answer) {
        return (ArrayNode) answer.get("tables");
    }

    private static ArrayNode elements(ObjectNode answer) {
        return (ArrayNode) answer.get("elements");
    }

    private static ObjectNode table(String shape, double cx, double cy, double width, double height, double rotation) {
        return item("shape", shape, cx, cy, width, height, rotation);
    }

    private static ObjectNode element(String type, double cx, double cy, double width, double height, double rotation) {
        return item("type", type, cx, cy, width, height, rotation);
    }

    private static ObjectNode item(String kindField, String kind, double cx, double cy, double width, double height,
                                   double rotation) {
        ObjectNode item = JSON.createObjectNode();
        item.put(kindField, kind);
        item.put("cx", cx).put("cy", cy).put("width", width).put("height", height).put("rotation", rotation);
        return item;
    }
}
