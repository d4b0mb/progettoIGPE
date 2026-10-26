package it.ristomanager.cloud.floor;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.floor.draft.FloorDraft;
import it.ristomanager.cloud.floor.draft.FloorDraftService;
import it.ristomanager.cloud.security.CurrentUser;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Proposes tables and fixed elements from a photo of a room. It lives in the floor package only because the
 * room lookup uses {@link FloorRepository}; it never writes anything, the owner reviews the draft on screen first.
 */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
class FloorDraftController {

    private final FloorRepository floor;
    private final FloorDraftService drafts;

    FloorDraftController(FloorRepository floor, FloorDraftService drafts) {
        this.floor = floor;
        this.drafts = drafts;
    }

    @PostMapping(value = "/rooms/{roomId}/floor-draft", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    FloorDraft floorDraft(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roomId,
                     @RequestPart("image") MultipartFile image, @RequestParam("roomWidthMeters") double roomWidthMeters) {
        if (!(roomWidthMeters >= 1 && roomWidthMeters <= 200)) {
            throw ApiException.invalid("roomWidthMeters", "roomWidthMeters must be between 1 and 200");
        }
        if (image.isEmpty()) {
            throw ApiException.invalid("image", "Choose an image to upload");
        }
        floor.findRoom(CurrentUser.from(jwt).restaurantId(), roomId)
                .orElseThrow(() -> ApiException.notFound("No such room"));
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw ApiException.invalid("image", "The image could not be read");
        }
        try {
            return drafts.draft(bytes, roomWidthMeters);
        } catch (AiException e) {
            throw switch (e.kind()) {
                case DISABLED, UNREACHABLE ->
                        new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The local AI model is not available");
                case TIMEOUT -> new ApiException(HttpStatus.GATEWAY_TIMEOUT, "The AI model took too long");
                case BAD_RESPONSE -> new ApiException(HttpStatus.BAD_GATEWAY, "The AI model gave an unusable answer");
            };
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.valueOf(422), "The room's walls could not be found in the photo");
        }
    }
}
