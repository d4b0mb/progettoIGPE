package it.ristomanager.cloud.auth;

import it.ristomanager.cloud.auth.AuthDtos.ChangePasswordRequest;
import it.ristomanager.cloud.auth.AuthDtos.LoginRequest;
import it.ristomanager.cloud.auth.AuthDtos.SignupRequest;
import it.ristomanager.cloud.auth.AuthDtos.TokenResponse;
import it.ristomanager.cloud.security.CurrentUser;
import it.ristomanager.cloud.security.SecurityProperties;
import it.ristomanager.cloud.user.UserRepository;
import it.ristomanager.cloud.user.UserView;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuthController {

    static final String REFRESH_COOKIE = "rm_refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final UserRepository users;
    private final SecurityProperties props;

    AuthController(AuthService auth, UserRepository users, SecurityProperties props) {
        this.auth = auth;
        this.users = users;
        this.props = props;
    }

    @PostMapping("/auth/signup")
    ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest req) {
        var session = auth.signup(req.restaurantName(), req.ownerName(), req.email(), req.password());
        return respond(HttpStatus.CREATED, session);
    }

    @PostMapping("/auth/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return respond(HttpStatus.OK, auth.login(req.email(), req.password()));
    }

    @PostMapping("/auth/refresh")
    ResponseEntity<TokenResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        return respond(HttpStatus.OK, auth.refresh(token));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        auth.logout(token);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    @PostMapping("/me/password")
    ResponseEntity<TokenResponse> changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
        return respond(HttpStatus.OK, auth.changePassword(CurrentUser.from(jwt), req.currentPassword(), req.newPassword()));
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser me = CurrentUser.from(jwt);
        return users.findById(me.userId()).map(UserView::of)
                .orElseThrow(() -> it.ristomanager.cloud.common.ApiException.unauthorized("Not signed in"));
    }

    private ResponseEntity<TokenResponse> respond(HttpStatus status, AuthService.Session s) {
        var body = new TokenResponse(s.access().value(), "Bearer", s.access().expiresInSeconds(), UserView.of(s.user()));
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie(s.refreshToken(), props.refreshTtl()).toString())
                .body(body);
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
