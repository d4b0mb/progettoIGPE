package it.ristomanager.cloud.reservation;

import it.ristomanager.cloud.floor.FloorTypes.DiningTable;
import it.ristomanager.cloud.reservation.ReservationTypes.Draft;
import it.ristomanager.cloud.reservation.ReservationTypes.Reservation;
import it.ristomanager.cloud.security.CurrentUser;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owner, manager and waiter can read; only owner and manager change reservations. Kitchen has no access. */
@RestController
@RequestMapping("/api/v1/reservations")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'WAITER')")
class ReservationController {

    record StatusChange(ReservationStatus status) {
    }

    private final ReservationService service;

    ReservationController(ReservationService service) {
        this.service = service;
    }

    @GetMapping
    List<Reservation> list(@AuthenticationPrincipal Jwt jwt,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.listForDay(CurrentUser.from(jwt).restaurantId(), date);
    }

    @GetMapping("/{id}")
    Reservation get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(CurrentUser.from(jwt).restaurantId(), id);
    }

    @GetMapping("/available-tables")
    List<DiningTable> availableTables(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startsAt,
                                      @RequestParam(required = false) Integer durationMinutes,
                                      @RequestParam int partySize,
                                      @RequestParam(required = false) UUID excludeReservationId) {
        return service.availableTables(CurrentUser.from(jwt).restaurantId(), startsAt, durationMinutes,
                partySize, excludeReservationId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    ResponseEntity<Reservation> create(@AuthenticationPrincipal Jwt jwt, @RequestBody Draft draft) {
        CurrentUser me = CurrentUser.from(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(me.restaurantId(), me.userId(), draft));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    Reservation update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody Draft draft) {
        return service.update(CurrentUser.from(jwt).restaurantId(), id, draft);
    }

    /** A waiter can mark a party as arrived without being able to edit the booking. */
    @PatchMapping("/{id}/status")
    Reservation changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody StatusChange body) {
        return service.changeStatus(CurrentUser.from(jwt).restaurantId(), id, body.status());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(CurrentUser.from(jwt).restaurantId(), id);
        return ResponseEntity.noContent().build();
    }
}
