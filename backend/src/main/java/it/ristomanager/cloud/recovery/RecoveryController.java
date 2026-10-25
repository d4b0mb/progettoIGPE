package it.ristomanager.cloud.recovery;

import it.ristomanager.cloud.recovery.RecoveryRepository.Questions;
import it.ristomanager.cloud.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RecoveryController {

    record RecoveryQuestionsRequest(@NotBlank @Email String email) {
    }

    record RecoveryQuestionsResponse(List<String> questions) {
    }

    record RecoveryResetRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(max = 200) String answer1,
            @NotBlank @Size(max = 200) String answer2,
            @NotBlank String newPassword) {
    }

    record RecoverySetupRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(max = 200) String question1,
            @NotBlank @Size(max = 200) String answer1,
            @NotBlank @Size(max = 200) String question2,
            @NotBlank @Size(max = 200) String answer2) {
    }

    /** What the owner has set up. The answers are never sent anywhere. */
    record RecoveryStatus(boolean configured, @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String question1,
                          @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String question2,
                          @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Instant updatedAt) {
    }

    private final RecoveryService service;

    RecoveryController(RecoveryService service) {
        this.service = service;
    }

    /** Public: the questions to answer for this email (real or decoy; the caller cannot tell which). */
    @PostMapping("/api/v1/auth/recovery/questions")
    RecoveryQuestionsResponse questions(@Valid @RequestBody RecoveryQuestionsRequest req) {
        return new RecoveryQuestionsResponse(service.questionsFor(req.email()));
    }

    /** Public: set a new password by answering both questions. */
    @PostMapping("/api/v1/auth/recovery/reset")
    ResponseEntity<Void> reset(@Valid @RequestBody RecoveryResetRequest req, HttpServletRequest http) {
        service.reset(req.email(), req.answer1(), req.answer2(), req.newPassword(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/me/recovery")
    @PreAuthorize("hasRole('OWNER')")
    RecoveryStatus status(@AuthenticationPrincipal Jwt jwt) {
        return service.statusOf(CurrentUser.from(jwt))
                .map((Questions q) -> new RecoveryStatus(true, q.question1(), q.question2(), q.updatedAt()))
                .orElse(new RecoveryStatus(false, null, null, null));
    }

    @PutMapping("/api/v1/me/recovery")
    @PreAuthorize("hasRole('OWNER')")
    ResponseEntity<Void> setUp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RecoverySetupRequest req) {
        service.setUp(CurrentUser.from(jwt), req.currentPassword(), req.question1(), req.answer1(), req.question2(), req.answer2());
        return ResponseEntity.noContent().build();
    }
}
