package it.ristomanager.cloud.floor;

import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.floor.FloorTypes.DiningTable;
import it.ristomanager.cloud.floor.FloorTypes.Layout;
import it.ristomanager.cloud.floor.FloorTypes.LiveStatus;
import it.ristomanager.cloud.floor.FloorTypes.Obstacle;
import it.ristomanager.cloud.floor.FloorTypes.ObstacleType;
import it.ristomanager.cloud.floor.FloorTypes.Room;
import it.ristomanager.cloud.floor.FloorTypes.ServiceStatus;
import it.ristomanager.cloud.floor.FloorTypes.TableShape;
import it.ristomanager.cloud.floor.FloorTypes.TableView;
import it.ristomanager.cloud.reservation.ReservationRepository;
import it.ristomanager.cloud.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rooms, tables and non-bookable elements. Owner and manager edit; owner, manager and waiter read. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'WAITER')")
class FloorController {

    record RoomRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin("1") @DecimalMax("200") Double widthMeters,
            @NotNull @DecimalMin("1") @DecimalMax("200") Double heightMeters,
            @DecimalMin("0.05") @DecimalMax("5") Double gridStepMeters) {
    }

    record TableRequest(
            @Min(1) Integer number,
            @Size(max = 40) String name,
            @NotNull @DecimalMin("-1000") @DecimalMax("1000") Double xMeters,
            @NotNull @DecimalMin("-1000") @DecimalMax("1000") Double yMeters,
            @NotNull @DecimalMin("0.2") @DecimalMax("50") Double widthMeters,
            @NotNull @DecimalMin("0.2") @DecimalMax("50") Double heightMeters,
            @DecimalMin("-360") @DecimalMax("360") Double rotationDegrees,
            @Min(1) @Max(100) Integer seats,
            @NotNull TableShape shape,
            Boolean accessible) {
    }

    record ObstacleRequest(
            @NotNull ObstacleType type,
            @Size(max = 40) String name,
            @NotNull @DecimalMin("-1000") @DecimalMax("1000") Double xMeters,
            @NotNull @DecimalMin("-1000") @DecimalMax("1000") Double yMeters,
            @NotNull @DecimalMin("0.05") @DecimalMax("200") Double widthMeters,
            @NotNull @DecimalMin("0.05") @DecimalMax("200") Double heightMeters,
            @DecimalMin("-360") @DecimalMax("360") Double rotationDegrees) {
    }

    record ServiceStatusRequest(@NotNull ServiceStatus serviceStatus) {
    }

    private final FloorRepository floor;
    private final ReservationRepository reservations;

    FloorController(FloorRepository floor, ReservationRepository reservations) {
        this.floor = floor;
        this.reservations = reservations;
    }

    private static UUID rid(Jwt jwt) {
        return CurrentUser.from(jwt).restaurantId();
    }

    // ---- rooms

    @GetMapping("/rooms")
    List<Room> rooms(@AuthenticationPrincipal Jwt jwt) {
        return floor.listRooms(rid(jwt));
    }

    @GetMapping("/rooms/{id}/layout")
    Layout layout(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID rid = rid(jwt);
        Room room = floor.findRoom(rid, id).orElseThrow(() -> ApiException.notFound("No such room"));
        Map<UUID, LiveStatus> live = reservations.liveStatuses(rid, Instant.now());
        List<TableView> tables = floor.listTables(rid, id).stream()
                .map(t -> new TableView(t, liveStatusOf(t, live)))
                .toList();
        return new Layout(room, tables, floor.listObstacles(rid, id));
    }

    private static LiveStatus liveStatusOf(DiningTable t, Map<UUID, LiveStatus> live) {
        return switch (t.serviceStatus()) {
            case OUT_OF_SERVICE -> LiveStatus.OUT_OF_SERVICE;
            case TO_CLEAN -> LiveStatus.TO_CLEAN;
            case FREE -> live.getOrDefault(t.id(), LiveStatus.FREE);
        };
    }

    @PostMapping("/rooms")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    ResponseEntity<Room> createRoom(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RoomRequest req) {
        try {
            Room room = floor.insertRoom(rid(jwt), req.name().trim(), req.widthMeters(), req.heightMeters(),
                    req.gridStepMeters() == null ? 0.25 : req.gridStepMeters());
            return ResponseEntity.status(HttpStatus.CREATED).body(room);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A room with this name already exists");
        }
    }

    @PutMapping("/rooms/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    Room updateRoom(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RoomRequest req) {
        try {
            return floor.updateRoom(rid(jwt), id, req.name().trim(), req.widthMeters(), req.heightMeters(),
                            req.gridStepMeters() == null ? 0.25 : req.gridStepMeters())
                    .orElseThrow(() -> ApiException.notFound("No such room"));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A room with this name already exists");
        }
    }

    @DeleteMapping("/rooms/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    @Transactional
    ResponseEntity<Void> deleteRoom(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID rid = rid(jwt);
        floor.findRoom(rid, id).orElseThrow(() -> ApiException.notFound("No such room"));
        if (floor.roomHasElements(rid, id)) {
            throw ApiException.conflict("Remove the tables and elements in this room first");
        }
        floor.deleteRoom(rid, id);
        return ResponseEntity.noContent().build();
    }

    // ---- tables

    @PostMapping("/rooms/{roomId}/tables")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    @Transactional
    ResponseEntity<DiningTable> createTable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId,
                                            @Valid @RequestBody TableRequest req) {
        UUID rid = rid(jwt);
        floor.findRoom(rid, roomId).orElseThrow(() -> ApiException.notFound("No such room"));
        DiningTable draft = toTable(req, req.number() == null ? floor.nextTableNumber(rid) : req.number(), roomId);
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(floor.insertTable(rid, roomId, draft));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Table number " + draft.number() + " is already in use");
        }
    }

    @PutMapping("/tables/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    @Transactional
    DiningTable updateTable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody TableRequest req) {
        UUID rid = rid(jwt);
        DiningTable existing = floor.findTable(rid, id).orElseThrow(() -> ApiException.notFound("No such table"));
        DiningTable draft = toTable(req, req.number() == null ? existing.number() : req.number(), existing.roomId());
        try {
            return floor.updateTable(rid, id, draft).orElseThrow(() -> ApiException.notFound("No such table"));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Table number " + draft.number() + " is already in use");
        }
    }

    /** Waiters can flag a table as needing cleaning or out of service. */
    @PatchMapping("/tables/{id}/service-status")
    DiningTable setServiceStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody ServiceStatusRequest req) {
        return floor.setServiceStatus(rid(jwt), id, req.serviceStatus())
                .orElseThrow(() -> ApiException.notFound("No such table"));
    }

    @DeleteMapping("/tables/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    ResponseEntity<Void> deleteTable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!floor.deleteTable(rid(jwt), id)) {
            throw ApiException.notFound("No such table");
        }
        return ResponseEntity.noContent().build();
    }

    private static DiningTable toTable(TableRequest r, int number, UUID roomId) {
        double height = TableRules.normalizedHeight(r.shape(), r.widthMeters(), r.heightMeters());
        int seats = r.seats() != null ? r.seats() : TableRules.suggestedSeats(r.shape(), r.widthMeters(), height);
        return new DiningTable(null, roomId, number, r.name() == null ? "" : r.name().trim(),
                r.xMeters(), r.yMeters(), r.widthMeters(), height,
                r.rotationDegrees() == null ? 0 : r.rotationDegrees(), seats, r.shape(),
                Boolean.TRUE.equals(r.accessible()), ServiceStatus.FREE);
    }

    // ---- obstacles

    @PostMapping("/rooms/{roomId}/obstacles")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    @Transactional
    ResponseEntity<Obstacle> createObstacle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId,
                                            @Valid @RequestBody ObstacleRequest req) {
        UUID rid = rid(jwt);
        floor.findRoom(rid, roomId).orElseThrow(() -> ApiException.notFound("No such room"));
        return ResponseEntity.status(HttpStatus.CREATED).body(floor.insertObstacle(rid, roomId, toObstacle(req, roomId)));
    }

    @PutMapping("/obstacles/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    Obstacle updateObstacle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ObstacleRequest req) {
        return floor.updateObstacle(rid(jwt), id, toObstacle(req, null))
                .orElseThrow(() -> ApiException.notFound("No such element"));
    }

    @DeleteMapping("/obstacles/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    ResponseEntity<Void> deleteObstacle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!floor.deleteObstacle(rid(jwt), id)) {
            throw ApiException.notFound("No such element");
        }
        return ResponseEntity.noContent().build();
    }

    private static Obstacle toObstacle(ObstacleRequest r, UUID roomId) {
        return new Obstacle(null, roomId, r.type(), r.name() == null ? "" : r.name().trim(),
                r.xMeters(), r.yMeters(), r.widthMeters(), r.heightMeters(),
                r.rotationDegrees() == null ? 0 : r.rotationDegrees());
    }
}
