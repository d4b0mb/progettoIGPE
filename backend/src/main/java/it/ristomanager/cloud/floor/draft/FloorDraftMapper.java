package it.ristomanager.cloud.floor.draft;

import it.ristomanager.cloud.floor.FloorTypes.ObstacleType;
import it.ristomanager.cloud.floor.FloorTypes.TableShape;
import it.ristomanager.cloud.floor.TableRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Turns the model's answer (fractions of the image) into a draft in metres. Pure arithmetic with no model and no
 * I/O, so every rule can be unit-tested. The model is never trusted: anything implausible is dropped and reported.
 */
final class FloorDraftMapper {

    static final int MAX_TABLES = 80;
    static final int MAX_ELEMENTS = 40;

    private static final double GRID = 0.05;
    private static final double ROTATION_STEP = 15;
    private static final int MIN_ROOM_PIXELS = 50;
    private static final double MIN_TABLE_SIDE = 0.4;
    private static final double MAX_TABLE_SIDE = 6;
    private static final double MIN_ELEMENT_SIDE = 0.1;
    private static final double OUTSIDE_TOLERANCE = 0.1;
    private static final double DUPLICATE_OVERLAP = 0.6;
    private static final int MAX_NOTE_LENGTH = 200;
    private static final String ROOM_NOT_FOUND = "room not found";

    private FloorDraftMapper() {
    }

    /** Counts of what was left out, so each kind produces one warning. */
    private static final class Dropped {
        int unknownKind;
        int implausibleSize;
        int outside;
        int duplicates;
        int tablesOverCap;
        int elementsOverCap;
    }

    private record Scale(int imageWidthPx, int imageHeightPx, double metresPerPx, double left, double top,
                         double roomWidth, double roomHeight) {

        double x(JsonNode item) {
            return (item.path("cx").asDouble(0) - left) * imageWidthPx * metresPerPx;
        }

        double y(JsonNode item) {
            return (item.path("cy").asDouble(0) - top) * imageHeightPx * metresPerPx;
        }

        double width(JsonNode item) {
            return item.path("width").asDouble(0) * imageWidthPx * metresPerPx;
        }

        double height(JsonNode item) {
            return item.path("height").asDouble(0) * imageHeightPx * metresPerPx;
        }

        boolean centreOutside(double x, double y) {
            return x < -OUTSIDE_TOLERANCE || x > roomWidth + OUTSIDE_TOLERANCE
                    || y < -OUTSIDE_TOLERANCE || y > roomHeight + OUTSIDE_TOLERANCE;
        }

        double clampX(double x) {
            return Math.min(Math.max(x, 0), roomWidth);
        }

        double clampY(double y) {
            return Math.min(Math.max(y, 0), roomHeight);
        }
    }

    /**
     * @param modelAnswer the parsed JSON the model returned
     * @throws IllegalArgumentException with the message "room not found" when the room's walls cannot be used
     */
    static FloorDraft toDraft(JsonNode modelAnswer, int imageWidthPx, int imageHeightPx, double roomWidthMeters) {
        JsonNode room = modelAnswer.path("room");
        double left = room.path("left").asDouble(0);
        double top = room.path("top").asDouble(0);
        double right = room.path("right").asDouble(0);
        double bottom = room.path("bottom").asDouble(0);
        double roomPxWidth = (right - left) * imageWidthPx;
        if (right <= left || bottom <= top || roomPxWidth < MIN_ROOM_PIXELS) {
            throw new IllegalArgumentException(ROOM_NOT_FOUND);
        }
        double metresPerPx = roomWidthMeters / roomPxWidth;
        double roomHeight = snap((bottom - top) * imageHeightPx * metresPerPx);
        if (roomHeight < 1 || roomHeight > 200) {
            throw new IllegalArgumentException(ROOM_NOT_FOUND);
        }
        Scale scale = new Scale(imageWidthPx, imageHeightPx, metresPerPx, left, top, roomWidthMeters, roomHeight);
        Dropped dropped = new Dropped();

        JsonNode tableNodes = modelAnswer.path("tables");
        List<DraftTable> tables = readTables(tableNodes, scale, dropped);
        List<DraftElement> elements = readElements(modelAnswer.path("elements"), scale, dropped);

        List<String> warnings = new ArrayList<>();
        if (!tableNodes.isArray() || tableNodes.size() == 0) {
            warnings.add("No tables were found in the photo");
        }
        addCount(warnings, dropped.unknownKind, "Ignored %d item%s of an unknown kind");
        addCount(warnings, dropped.implausibleSize, "Ignored %d item%s with an implausible size");
        addCount(warnings, dropped.outside, "Ignored %d item%s outside the room");
        addCount(warnings, dropped.duplicates, "Ignored %d duplicate table%s");
        addCount(warnings, dropped.tablesOverCap, "Ignored %d table%s beyond the limit of " + MAX_TABLES);
        addCount(warnings, dropped.elementsOverCap, "Ignored %d element%s beyond the limit of " + MAX_ELEMENTS);
        String note = modelAnswer.path("notes").textValue();
        if (note != null && !note.isBlank()) {
            String trimmed = note.strip();
            warnings.add("Model note: " + (trimmed.length() > MAX_NOTE_LENGTH ? trimmed.substring(0, MAX_NOTE_LENGTH) : trimmed));
        }
        return new FloorDraft(roomWidthMeters, roomHeight, List.copyOf(tables), List.copyOf(elements), List.copyOf(warnings));
    }

    private static List<DraftTable> readTables(JsonNode nodes, Scale scale, Dropped dropped) {
        List<DraftTable> tables = new ArrayList<>();
        if (!nodes.isArray()) {
            return tables;
        }
        for (JsonNode node : nodes) {
            TableShape shape = enumOf(TableShape.class, node.path("shape").textValue());
            if (shape == null) {
                dropped.unknownKind++;
                continue;
            }
            double width = snap(scale.width(node));
            double height = TableRules.normalizedHeight(shape, width, snap(scale.height(node)));
            if (width < MIN_TABLE_SIDE || width > MAX_TABLE_SIDE || height < MIN_TABLE_SIDE || height > MAX_TABLE_SIDE) {
                dropped.implausibleSize++;
                continue;
            }
            double rawX = scale.x(node);
            double rawY = scale.y(node);
            if (scale.centreOutside(rawX, rawY)) {
                dropped.outside++;
                continue;
            }
            double x = scale.clampX(snap(rawX));
            double y = scale.clampY(snap(rawY));
            if (overlapsEarlier(tables, x, y, width, height)) {
                dropped.duplicates++;
                continue;
            }
            if (tables.size() >= MAX_TABLES) {
                dropped.tablesOverCap++;
                continue;
            }
            tables.add(new DraftTable(shape.name(), x, y, width, height, rotation(node),
                    TableRules.suggestedSeats(shape, width, height)));
        }
        return tables;
    }

    private static List<DraftElement> readElements(JsonNode nodes, Scale scale, Dropped dropped) {
        List<DraftElement> elements = new ArrayList<>();
        if (!nodes.isArray()) {
            return elements;
        }
        for (JsonNode node : nodes) {
            ObstacleType type = enumOf(ObstacleType.class, node.path("type").textValue());
            if (type == null) {
                dropped.unknownKind++;
                continue;
            }
            double width = snap(scale.width(node));
            double height = snap(scale.height(node));
            if ((width < MIN_ELEMENT_SIDE && height < MIN_ELEMENT_SIDE)
                    || width > scale.roomWidth() || height > scale.roomHeight()) {
                dropped.implausibleSize++;
                continue;
            }
            double rawX = scale.x(node);
            double rawY = scale.y(node);
            if (scale.centreOutside(rawX, rawY)) {
                dropped.outside++;
                continue;
            }
            if (elements.size() >= MAX_ELEMENTS) {
                dropped.elementsOverCap++;
                continue;
            }
            // A thin wall can snap to zero thickness; the floor plan needs at least one grid step.
            elements.add(new DraftElement(type.name(), scale.clampX(snap(rawX)), scale.clampY(snap(rawY)),
                    Math.max(width, GRID), Math.max(height, GRID), rotation(node)));
        }
        return elements;
    }

    /** Axis-aligned approximation: rotation is ignored. */
    private static boolean overlapsEarlier(List<DraftTable> earlier, double x, double y, double width, double height) {
        for (DraftTable other : earlier) {
            double overlapWidth = Math.min(x + width / 2, other.xMeters() + other.widthMeters() / 2)
                    - Math.max(x - width / 2, other.xMeters() - other.widthMeters() / 2);
            double overlapHeight = Math.min(y + height / 2, other.yMeters() + other.heightMeters() / 2)
                    - Math.max(y - height / 2, other.yMeters() - other.heightMeters() / 2);
            if (overlapWidth <= 0 || overlapHeight <= 0) {
                continue;
            }
            double smaller = Math.min(width * height, other.widthMeters() * other.heightMeters());
            if (overlapWidth * overlapHeight > DUPLICATE_OVERLAP * smaller) {
                return true;
            }
        }
        return false;
    }

    private static double rotation(JsonNode item) {
        double snapped = Math.round(item.path("rotation").asDouble(0) / ROTATION_STEP) * ROTATION_STEP;
        return ((snapped % 360) + 360) % 360;
    }

    /** Nearest multiple of 0.05 m; dividing by 20 keeps results like 0.15 exact instead of 0.15000000000000002. */
    private static double snap(double metres) {
        return Math.round(metres / GRID) / 20.0;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void addCount(List<String> warnings, int count, String template) {
        if (count > 0) {
            warnings.add(String.format(Locale.ROOT, template, count, count == 1 ? "" : "s"));
        }
    }
}
