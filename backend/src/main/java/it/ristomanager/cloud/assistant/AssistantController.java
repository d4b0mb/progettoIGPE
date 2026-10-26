package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.assistant.AssistantData.BookingResult;
import it.ristomanager.cloud.assistant.AssistantTypes.AssistantReply;
import it.ristomanager.cloud.assistant.AssistantTypes.AssistantRequest;
import it.ristomanager.cloud.assistant.AssistantTypes.ConfirmResult;
import it.ristomanager.cloud.assistant.ProposalStore.Proposal;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.security.CurrentUser;
import it.ristomanager.cloud.user.Role;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff assistant. Everyone on staff may chat (what the model can look up depends on their role); only owner and
 * manager can confirm a proposal, exactly as only they can create or cancel reservations on the screens.
 *
 * {@code /chat} never changes a reservation. A proposal becomes real only through {@code /proposals/{id}/confirm}, a
 * separate authenticated call made when the person presses the Confirm button.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'WAITER', 'KITCHEN')")
class AssistantController {

    private final AssistantService assistant;
    private final ProposalStore proposals;
    private final AssistantData data;
    private final AssistantAudit audit;

    AssistantController(AssistantService assistant, ProposalStore proposals, AssistantData data, AssistantAudit audit) {
        this.assistant = assistant;
        this.proposals = proposals;
        this.data = data;
        this.audit = audit;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    AssistantReply chat(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AssistantRequest request) {
        return assistant.chat(CurrentUser.from(jwt), request);
    }

    /**
     * Carries out a proposal. It is taken from the store for the current person and restaurant only (anyone else gets 404),
     * is single use, and the role is checked again now. A refusal from the reservation rules (409 the table was just
     * booked, 400 a day that has passed) is passed on as it is.
     */
    @PostMapping("/proposals/{id}/confirm")
    ConfirmResult confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        CurrentUser me = CurrentUser.from(jwt);
        Proposal proposal = proposals.take(id, me.userId(), me.restaurantId());
        if (me.role() != Role.OWNER && me.role() != Role.MANAGER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the owner or a manager can confirm this");
        }
        BookingResult done = switch (proposal.kind()) {
            case CREATE_RESERVATION -> data.createReservation(me.restaurantId(), me.userId(), proposal.draft());
            case CANCEL_RESERVATION -> data.cancelReservation(me.restaurantId(), proposal.reservationId());
        };
        audit.confirmed(proposal.kind().name(), done.id());
        return new ConfirmResult(proposal.kind().name(), done.guestName(), done.partySize(), done.date(), done.time(),
                done.tableNumber());
    }

    /** Forgets a proposal without doing it. Always 204, also when it has expired or never existed. */
    @DeleteMapping("/proposals/{id}")
    ResponseEntity<Void> dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        CurrentUser me = CurrentUser.from(jwt);
        proposals.dismiss(id, me.userId(), me.restaurantId());
        return ResponseEntity.noContent().build();
    }
}
