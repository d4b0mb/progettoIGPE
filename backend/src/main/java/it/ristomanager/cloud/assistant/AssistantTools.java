package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.common.Json;
import it.ristomanager.cloud.ai.ToolCall;
import it.ristomanager.cloud.ai.ToolDefinition;
import it.ristomanager.cloud.assistant.AssistantData.ReservationDraft;
import it.ristomanager.cloud.assistant.AssistantData.ReservationRow;
import it.ristomanager.cloud.assistant.AssistantData.TableChoice;
import it.ristomanager.cloud.assistant.ProposalStore.Kind;
import it.ristomanager.cloud.assistant.ProposalStore.Proposal;
import it.ristomanager.cloud.assistant.ProposalStore.Summary;
import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.user.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools the model may call, and the code that runs them.
 *
 * Three rules hold everywhere in here:
 * <ul>
 *   <li>The model's arguments are untrusted text: every one is validated, and a bad one becomes an
 *       {"error": ...} result the model can relay, never an exception.</li>
 *   <li>Results hold no phone numbers or e-mail addresses, and anything a guest wrote (names, notes) sits under a key that
 *       starts with {@code guest_written_} and is cut short, so the model can tell information from instructions.</li>
 *   <li>The two propose_ tools write nothing to the reservations. They only add a proposal to the {@link ProposalStore};
 *       a person pressing Confirm in a separate request is what executes it.</li>
 * </ul>
 * Which tools a role may use is decided twice: {@link #definitions} decides what the model is shown, and
 * {@link #execute} re-checks before touching any data, so a call the model was never offered is refused.
 */
final class AssistantTools {

    static final String FIND_RESERVATIONS = "find_reservations";
    static final String FLOOR_STATUS = "floor_status";
    static final String FREE_TABLES = "free_tables";
    static final String KITCHEN_QUEUE = "kitchen_queue";
    static final String MENU_LOOKUP = "menu_lookup";
    static final String PROPOSE_CREATE = "propose_create_reservation";
    static final String PROPOSE_CANCEL = "propose_cancel_reservation";

    static final int NAME_MAX = 80;
    private static final int MAX_FREE_TABLES = 30;
    static final int NOTES_MAX = 160;
    private static final int NOTES_INPUT_MAX = 300;
    private static final int PHONE_MAX = 30;
    private static final int MAX_PARTY_SIZE = 100;
    private static final Pattern TIME = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");

    private static final Logger log = LoggerFactory.getLogger(AssistantTools.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final List<String> ALL_TOOLS = List.of(
            FIND_RESERVATIONS, FLOOR_STATUS, FREE_TABLES, KITCHEN_QUEUE, MENU_LOOKUP, PROPOSE_CREATE, PROPOSE_CANCEL);

    /** Matches what the REST endpoints allow: floor and reservation reads for owner, manager and waiter; writes for owner and manager. */
    private static final Map<Role, Set<String>> ALLOWED = Map.of(
            Role.OWNER, Set.copyOf(ALL_TOOLS),
            Role.MANAGER, Set.copyOf(ALL_TOOLS),
            Role.WAITER, Set.of(FIND_RESERVATIONS, FLOOR_STATUS, FREE_TABLES, KITCHEN_QUEUE, MENU_LOOKUP),
            Role.KITCHEN, Set.of(KITCHEN_QUEUE, MENU_LOOKUP));

    private static final Map<String, ToolDefinition> DEFINITIONS = buildDefinitions();

    /** Who is asking, and where to put the proposals made during this one request. */
    record Context(UUID restaurantId, UUID userId, Role role, ZoneId zone, List<Proposal> proposals) {
    }

    /** What goes back to the model (compact JSON text) and a one-word outcome for the activity log. */
    record Result(String json, String outcome) {
    }

    private final AssistantData data;
    private final ProposalStore proposalStore;

    AssistantTools(AssistantData data, ProposalStore proposalStore) {
        this.data = data;
        this.proposalStore = proposalStore;
    }

    // ---- what the model is shown

    /** The tools this role may use, in a fixed order. */
    List<ToolDefinition> definitions(Role role) {
        Set<String> allowed = ALLOWED.getOrDefault(role, Set.of());
        return ALL_TOOLS.stream().filter(allowed::contains).map(DEFINITIONS::get).toList();
    }

    static boolean isKnown(String name) {
        return ALL_TOOLS.contains(name);
    }

    private static Map<String, ToolDefinition> buildDefinitions() {
        ObjectNode allergenItems = MAPPER.createObjectNode().put("type", "string");
        ArrayNode names = allergenItems.putArray("enum");
        for (Allergen a : Allergen.values()) {
            names.add(a.name());
        }
        ObjectNode allergens = MAPPER.createObjectNode().put("type", "array")
                .put("description", "Allergens the guest declared (optional)");
        allergens.set("items", allergenItems);

        Map<String, ToolDefinition> map = new java.util.LinkedHashMap<>();
        map.put(FIND_RESERVATIONS, tool(FIND_RESERVATIONS, "Look up bookings by date and/or guest name.",
                props("date", string("YYYY-MM-DD"), "name", string("Guest name or part of it"))));
        map.put(FLOOR_STATUS, tool(FLOOR_STATUS,
                "Which tables are free, reserved or occupied RIGHT NOW. Describes this moment only: for any other time or day use free_tables.",
                props()));
        map.put(FREE_TABLES, tool(FREE_TABLES,
                "Which tables are free at a given day and time, for a party size, assuming the usual two-hour stay. Use it for every "
                        + "question about a time that is not right now (lunch, dinner, tonight at 9, Saturday).",
                props("date", string("YYYY-MM-DD; today when the person gives no day"),
                        "time", string("HH:MM, 24-hour; lunch is 13:00 and dinner 20:00 when the person gives no hour"),
                        "party_size", MAPPER.createObjectNode().put("type", "integer").put("description", "Number of guests; 1 when not said")),
                "time"));
        map.put(KITCHEN_QUEUE, tool(KITCHEN_QUEUE, "Orders waiting in the kitchen.", props()));
        map.put(MENU_LOOKUP, tool(MENU_LOOKUP,
                "Look up a menu item or search the menu: price and declared allergens.",
                props("query", string("Dish name or search words")), "query"));
        map.put(PROPOSE_CREATE, tool(PROPOSE_CREATE,
                "Prepare a booking for the person to confirm. Does NOT create it.",
                props("guest_name", string("Guest surname or name"),
                        "party_size", MAPPER.createObjectNode().put("type", "integer"),
                        "date", string("YYYY-MM-DD"),
                        "time", string("HH:MM, 24-hour"),
                        "phone", string("Optional"),
                        "notes", string("Optional: requests"),
                        "allergens", allergens),
                "guest_name", "party_size", "date", "time"));
        map.put(PROPOSE_CANCEL, tool(PROPOSE_CANCEL,
                "Prepare the cancellation of a booking for the person to confirm. Does NOT cancel it.",
                props("guest_name", string("Guest name"), "date", string("YYYY-MM-DD, if known")), "guest_name"));
        return map;
    }

    private static ObjectNode string(String description) {
        return MAPPER.createObjectNode().put("type", "string").put("description", description);
    }

    private static ObjectNode props(Object... namesAndSchemas) {
        ObjectNode properties = MAPPER.createObjectNode();
        for (int i = 0; i < namesAndSchemas.length; i += 2) {
            properties.set((String) namesAndSchemas[i], (JsonNode) namesAndSchemas[i + 1]);
        }
        return properties;
    }

    private static ToolDefinition tool(String name, String description, ObjectNode properties, String... required) {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        ArrayNode requiredNames = schema.putArray("required");
        for (String r : required) {
            requiredNames.add(r);
        }
        return new ToolDefinition(name, description, schema);
    }

    // ---- running a call

    Result execute(Context ctx, ToolCall call) {
        String name = call.name();
        if (!isKnown(name)) {
            return error("unknown tool", "unknown_tool");
        }
        if (!ALLOWED.getOrDefault(ctx.role(), Set.of()).contains(name)) {
            return error("not allowed", "not_allowed");
        }
        JsonNode args = call.arguments();
        try {
            return switch (name) {
                case FIND_RESERVATIONS -> findReservations(ctx, args);
                case FLOOR_STATUS -> floorStatus(ctx);
                case FREE_TABLES -> freeTables(ctx, args);
                case KITCHEN_QUEUE -> kitchenQueue(ctx);
                case MENU_LOOKUP -> menuLookup(ctx, args);
                case PROPOSE_CREATE -> proposeCreate(ctx, args);
                case PROPOSE_CANCEL -> proposeCancel(ctx, args);
                default -> error("unknown tool", "unknown_tool");
            };
        } catch (BadArgument e) {
            return error(e.getMessage(), "error");
        } catch (ApiException e) {
            // A reservation rule said no (for example a day that has passed). The message is ours and holds no guest data.
            return error(TextFold.cut(e.getMessage(), 160), "error");
        } catch (RuntimeException e) {
            // Only the kind of failure is logged: messages could carry guest data.
            log.warn("Assistant tool failed: tool={} type={}", name, e.getClass().getSimpleName());
            return error("the lookup failed, try again", "error");
        }
    }

    private Result findReservations(Context ctx, JsonNode args) {
        LocalDate date = optionalDate(args, "date");
        String name = optionalText(args, "name", NAME_MAX, false);
        List<ReservationRow> rows = data.findReservations(ctx.restaurantId(), date, name);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("count", rows.size());
        ArrayNode list = out.putArray("reservations");
        for (ReservationRow row : rows) {
            ObjectNode node = list.addObject();
            node.put("date", row.date().toString());
            node.put("time", row.time());
            node.put("party_size", row.partySize());
            node.put("status", row.status());
            if (row.tableNumber() != null) {
                node.put("table", row.tableNumber().intValue());
            }
            ArrayNode declared = node.putArray("declared_allergens");
            row.allergens().forEach(declared::add);
            node.put("guest_written_name", TextFold.cut(row.guestName(), NAME_MAX));
            String notes = TextFold.cut(row.notes(), NOTES_MAX);
            if (!notes.isBlank()) {
                node.put("guest_written_notes", notes);
            }
        }
        return ok(out);
    }

    private Result floorStatus(Context ctx) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode list = out.putArray("tables");
        data.floorStatus(ctx.restaurantId()).stream().limit(80).forEach(t -> {
            ObjectNode node = list.addObject();
            node.put("number", t.number());
            node.put("seats", t.seats());
            node.put("status", t.status());
        });
        return ok(out);
    }

    private Result kitchenQueue(Context ctx) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode list = out.putArray("tickets");
        data.kitchenQueue(ctx.restaurantId()).forEach(t -> {
            ObjectNode node = list.addObject();
            node.put("code", t.code());
            if (t.tableNumber() != null) {
                node.put("table", t.tableNumber().intValue());
            }
            node.put("status", t.status());
            node.put("allergy_alarm", t.allergyAlarm());
            ArrayNode items = node.putArray("items");
            t.items().forEach(i -> items.addObject().put("qty", i.quantity()).put("name", TextFold.cut(i.name(), NAME_MAX)));
        });
        return ok(out);
    }

    private Result menuLookup(Context ctx, JsonNode args) {
        String query = requiredText(args, "query", NAME_MAX);
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode list = out.putArray("results");
        data.searchMenu(ctx.restaurantId(), query).forEach(m -> {
            ObjectNode node = list.addObject();
            node.put("name", TextFold.cut(m.name(), NAME_MAX));
            if (m.priceCents() == null) {
                node.putNull("price");
            } else {
                node.put("price", String.format(java.util.Locale.ROOT, "%d.%02d", m.priceCents() / 100, m.priceCents() % 100));
            }
            node.put("course", m.course());
            ArrayNode declared = node.putArray("declared_allergens");
            m.allergens().forEach(declared::add);
            if (m.description() != null && !m.description().isBlank()) {
                node.put("description", TextFold.cut(m.description(), 300));
            }
        });
        out.put("note", "Declared data only. Never say a dish is safe for an allergy; tell the person to confirm with the kitchen.");
        return ok(out);
    }

    /** Tables free at a chosen day and time. Past moments are refused: the answer would mean nothing. */
    private Result freeTables(Context ctx, JsonNode args) {
        LocalDate date = optionalDate(args, "date");
        if (date == null) {
            date = LocalDate.now(ctx.zone());
        }
        LocalTime time = requiredTime(args, "time");
        int partySize = absent(args.path("party_size")) ? 1 : partySize(args);
        Instant startsAt = date.atTime(time).atZone(ctx.zone()).toInstant();
        if (startsAt.isBefore(Instant.now())) {
            return error("that time has already passed", "error");
        }
        List<TableChoice> tables = data.availableTables(ctx.restaurantId(), startsAt, partySize);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("date", date.toString());
        out.put("time", time.toString());
        out.put("party_size", partySize);
        out.put("assumed_stay_minutes", 120);
        out.put("count", tables.size());
        ArrayNode list = out.putArray("free_tables");
        for (TableChoice t : tables.stream().limit(MAX_FREE_TABLES).toList()) {
            ObjectNode node = list.addObject();
            node.put("table", t.number());
            node.put("seats", t.seats());
        }
        return ok(out);
    }

    private Result proposeCreate(Context ctx, JsonNode args) {
        String guest = requiredText(args, "guest_name", NAME_MAX);
        int partySize = partySize(args);
        LocalDate date = requiredDate(args, "date");
        LocalTime time = requiredTime(args, "time");
        String phone = optionalText(args, "phone", PHONE_MAX, false);
        String notes = optionalText(args, "notes", NOTES_INPUT_MAX, true);
        List<Allergen> allergens = allergens(args);

        Instant startsAt = date.atTime(time).atZone(ctx.zone()).toInstant();
        Optional<TableChoice> table = data.availableTable(ctx.restaurantId(), startsAt, partySize);
        ReservationDraft draft = new ReservationDraft(guest, phone, startsAt, partySize,
                table.map(TableChoice::id).orElse(null), allergens, notes);
        // Refuse a proposal that could never be confirmed (a day that has passed, a party over the limit...).
        data.validateDraft(ctx.restaurantId(), draft);

        Summary summary = new Summary(guest, partySize, date.toString(), time.toString(),
                table.map(TableChoice::number).orElse(null), notes == null ? "" : notes);
        boolean already = ctx.proposals().stream().anyMatch(p -> p.kind() == Kind.CREATE_RESERVATION && draft.equals(p.draft()));
        if (!already) {
            ctx.proposals().add(proposalStore.add(Kind.CREATE_RESERVATION, ctx.userId(), ctx.restaurantId(), ctx.role(),
                    draft, null, summary));
        }
        return proposed("create", summary);
    }

    private Result proposeCancel(Context ctx, JsonNode args) {
        String guest = requiredText(args, "guest_name", NAME_MAX);
        LocalDate date = optionalDate(args, "date");
        List<ReservationRow> matches = data.findCancellable(ctx.restaurantId(), date, guest);
        if (matches.isEmpty()) {
            return error("no matching reservation", "error");
        }
        if (matches.size() > 1) {
            ObjectNode out = MAPPER.createObjectNode();
            ArrayNode list = out.putArray("candidates");
            for (ReservationRow row : matches) {
                ObjectNode node = list.addObject();
                node.put("date", row.date().toString());
                node.put("time", row.time());
                node.put("party_size", row.partySize());
            }
            out.put("hint", "Several bookings match. Ask which one (date and time). Nothing was proposed.");
            return new Result(write(out), "candidates");
        }
        ReservationRow row = matches.get(0);
        Summary summary = new Summary(TextFold.cut(row.guestName(), NAME_MAX), row.partySize(), row.date().toString(),
                row.time(), row.tableNumber(), TextFold.cut(row.notes(), NOTES_MAX));
        boolean already = ctx.proposals().stream().anyMatch(p -> p.kind() == Kind.CANCEL_RESERVATION && row.id().equals(p.reservationId()));
        if (!already) {
            ctx.proposals().add(proposalStore.add(Kind.CANCEL_RESERVATION, ctx.userId(), ctx.restaurantId(), ctx.role(),
                    null, row.id(), summary));
        }
        return proposed("cancel", summary);
    }

    /** The proposal's id is deliberately absent: the model never needs it, and it never sees it. */
    private static Result proposed(String action, Summary s) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("proposed", true);
        out.put("action", action);
        out.put("status", "awaiting confirmation by the person, not done yet");
        ObjectNode summary = out.putObject("summary");
        summary.put("guest_written_name", s.guestName());
        summary.put("party_size", s.partySize());
        summary.put("date", s.date());
        summary.put("time", s.time());
        if (s.tableNumber() == null) {
            summary.putNull("table");
        } else {
            summary.put("table", s.tableNumber().intValue());
        }
        if (s.notes() != null && !s.notes().isBlank()) {
            summary.put("guest_written_notes", TextFold.cut(s.notes(), NOTES_MAX));
        }
        return new Result(write(out), "proposed");
    }

    // ---- argument checks

    /** The model gave something unusable. The message is short and safe to hand back to it. */
    private static final class BadArgument extends RuntimeException {
        BadArgument(String message) {
            super(message, null, false, false);
        }
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static String clean(String text) {
        return text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * @param truncate cut a longer text instead of refusing it (used for free-text notes)
     * @return the cleaned text, or null when the argument is missing or blank
     */
    private static String optionalText(JsonNode args, String key, int max, boolean truncate) {
        JsonNode node = args.path(key);
        if (absent(node)) {
            return null;
        }
        String text = Json.text(node);
        if (text == null) {
            throw new BadArgument(key + " must be text");
        }
        text = clean(text);
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > max) {
            if (!truncate) {
                throw new BadArgument(key + " is too long (at most " + max + " characters)");
            }
            text = text.substring(0, max);
        }
        return text;
    }

    private static String requiredText(JsonNode args, String key, int max) {
        String text = optionalText(args, key, max, false);
        if (text == null) {
            throw new BadArgument(key + " is required");
        }
        return text;
    }

    private static LocalDate optionalDate(JsonNode args, String key) {
        String text = optionalText(args, key, 20, false);
        if (text == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(text);
            if (date.getYear() < 2000 || date.getYear() > 2100) {
                throw new BadArgument(key + " is not a plausible date");
            }
            return date;
        } catch (DateTimeParseException e) {
            throw new BadArgument(key + " must be a date like 2026-10-26");
        }
    }

    private static LocalDate requiredDate(JsonNode args, String key) {
        LocalDate date = optionalDate(args, key);
        if (date == null) {
            throw new BadArgument(key + " is required");
        }
        return date;
    }

    private static LocalTime requiredTime(JsonNode args, String key) {
        String text = requiredText(args, key, 10);
        Matcher m = TIME.matcher(text);
        if (!m.matches()) {
            throw new BadArgument(key + " must be a 24-hour time like 21:00");
        }
        return LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    /** A whole number from 1 to 100. A number written as text ("4") is accepted too: small models do that. */
    private static int partySize(JsonNode args) {
        JsonNode node = args.path("party_size");
        if (absent(node)) {
            throw new BadArgument("party_size is required");
        }
        double value;
        if (node.isNumber()) {
            value = node.asDouble(0);
        } else if (Json.text(node) != null && Json.text(node).trim().matches("\\d{1,3}")) {
            value = Integer.parseInt(Json.text(node).trim());
        } else {
            throw new BadArgument("party_size must be a whole number");
        }
        if (value != Math.rint(value)) {
            throw new BadArgument("party_size must be a whole number");
        }
        if (value < 1 || value > MAX_PARTY_SIZE) {
            throw new BadArgument("party_size must be between 1 and " + MAX_PARTY_SIZE);
        }
        return (int) value;
    }

    private static List<Allergen> allergens(JsonNode args) {
        JsonNode node = args.path("allergens");
        if (absent(node)) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new BadArgument("allergens must be a list");
        }
        if (node.size() > 14) {
            throw new BadArgument("allergens has too many entries");
        }
        Set<Allergen> found = new LinkedHashSet<>();
        List<String> valid = new ArrayList<>();
        for (Allergen a : Allergen.values()) {
            valid.add(a.name());
        }
        for (JsonNode entry : node) {
            String text = Json.text(entry);
            String upper = text == null ? "" : clean(text).toUpperCase(java.util.Locale.ROOT);
            if (!valid.contains(upper)) {
                throw new BadArgument("unknown allergen, use one of " + String.join(", ", valid));
            }
            found.add(Allergen.valueOf(upper));
        }
        return List.copyOf(found);
    }

    // ---- results

    private static Result ok(ObjectNode out) {
        return new Result(write(out), "ok");
    }

    private static Result error(String reason, String outcome) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("error", reason);
        return new Result(write(out), outcome);
    }

    private static String write(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }
}
