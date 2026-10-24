package it.ristomanager.cloud.order;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import it.ristomanager.cloud.order.OrderTypes.OrderRequest;
import it.ristomanager.cloud.order.OrderTypes.Status;
import it.ristomanager.cloud.order.OrderTypes.TicketView;
import it.ristomanager.cloud.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Orders ("tickets"): the floor sends them, the kitchen cooks them, everyone on staff can watch. */
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    record OrderStatusChange(Status status) {
    }

    private final OrderService service;
    private final TicketEvents events;

    OrderController(OrderService service, TicketEvents events) {
        this.service = service;
        this.events = events;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'WAITER')")
    ResponseEntity<TicketView> create(@AuthenticationPrincipal Jwt jwt, @RequestBody OrderRequest req) {
        CurrentUser me = CurrentUser.from(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(me.restaurantId(), me.userId(), req));
    }

    /** The kitchen queue: highest priority first, then oldest first. */
    @GetMapping("/active")
    List<TicketView> active(@AuthenticationPrincipal Jwt jwt) {
        return service.active(CurrentUser.from(jwt).restaurantId());
    }

    @GetMapping("/{id}")
    TicketView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(CurrentUser.from(jwt).restaurantId(), id);
    }

    @PatchMapping("/{id}/status")
    TicketView changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody OrderStatusChange body) {
        CurrentUser me = CurrentUser.from(jwt);
        return service.changeStatus(me.restaurantId(), id, me.role(), body.status());
    }

    /**
     * Live updates as Server-Sent Events: "ticket-created" and "ticket-updated".
     * After connecting, load /active to get the current queue; this stream only
     * carries what changes afterwards. It closes after a few minutes and the client
     * reconnects, which also re-checks that the login is still valid.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(responseCode = "200", description = "Server-sent events: ready, ticket-created, ticket-updated",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string")))
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        return events.subscribe(CurrentUser.from(jwt).restaurantId());
    }
}
