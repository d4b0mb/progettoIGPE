package it.ristomanager.cloud.auth;

import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.restaurant.RestaurantRepository;
import it.ristomanager.cloud.security.JwtService;
import it.ristomanager.cloud.security.JwtService.IssuedToken;
import it.ristomanager.cloud.security.SecurityProperties;
import it.ristomanager.cloud.user.AppUser;
import it.ristomanager.cloud.user.Role;
import it.ristomanager.cloud.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /** A session: the short-lived access token plus the raw refresh token for the cookie. */
    public record Session(AppUser user, IssuedToken access, String refreshToken) {
    }

    private final UserRepository users;
    private final RestaurantRepository restaurants;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final SecurityProperties props;
    private final SecureRandom random = new SecureRandom();

    /** Compared against when the email is unknown, so "no such user" costs the same time as "wrong password". */
    private final String dummyHash;

    AuthService(UserRepository users, RestaurantRepository restaurants, RefreshTokenRepository refreshTokens,
                PasswordEncoder encoder, JwtService jwt, SecurityProperties props) {
        this.users = users;
        this.restaurants = restaurants;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public Session signup(String restaurantName, String ownerName, String email, String password) {
        String normalized = normalizeEmail(email);
        if (users.emailExists(normalized)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        try {
            var restaurantId = restaurants.insert(restaurantName.trim());
            AppUser owner = users.insert(restaurantId, normalized, encoder.encode(password), ownerName.trim(), Role.OWNER);
            return startSession(owner);
        } catch (DuplicateKeyException e) {
            // Two signups with the same email racing each other.
            throw ApiException.conflict("An account with this email already exists");
        }
    }

    @Transactional
    public Session login(String email, String password) {
        Optional<AppUser> found = users.findByEmail(normalizeEmail(email));
        boolean passwordOk = encoder.matches(password, found.map(AppUser::passwordHash).orElse(dummyHash));
        if (found.isEmpty() || !passwordOk || !found.get().active()) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        return startSession(found.get());
    }

    /** Exchanges a refresh token for a new session; the old token is used up. */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("Not signed in");
        }
        var stored = refreshTokens.findByHash(hash(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("Not signed in"));
        if (stored.revoked()) {
            // A used-up token came back: someone copied it. Sign this user out everywhere.
            refreshTokens.revokeAllForUser(stored.userId());
            throw ApiException.unauthorized("Not signed in");
        }
        if (stored.expiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("Session expired");
        }
        refreshTokens.revoke(stored.id());
        AppUser user = users.findById(stored.userId())
                .filter(AppUser::active)
                .orElseThrow(() -> ApiException.unauthorized("Not signed in"));
        return startSession(user);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokens.findByHash(hash(rawToken)).ifPresent(t -> refreshTokens.revoke(t.id()));
    }

    public void revokeAllSessions(java.util.UUID userId) {
        refreshTokens.revokeAllForUser(userId);
    }

    private Session startSession(AppUser user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.insert(user.id(), hash(raw), Instant.now().plus(props.refreshTtl()));
        return new Session(user, jwt.issue(user), raw);
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
