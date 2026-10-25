package it.ristomanager.cloud.recovery;

import it.ristomanager.cloud.audit.AuditLog;
import it.ristomanager.cloud.auth.AuthService;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.recovery.RecoveryRepository.Questions;
import it.ristomanager.cloud.recovery.RecoveryRepository.Stored;
import it.ristomanager.cloud.security.CurrentUser;
import it.ristomanager.cloud.tenant.TenantContext;
import it.ristomanager.cloud.user.AppUser;
import it.ristomanager.cloud.user.PasswordPolicy;
import it.ristomanager.cloud.user.Role;
import it.ristomanager.cloud.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lets an owner who forgot their password set a new one by answering two questions they wrote themselves.
 *
 * Security questions are the weakest kind of recovery, so this is built defensively: the owner writes the
 * questions (not a list an attacker can look up), answers are stored only as BCrypt hashes, wrong tries are
 * throttled, every attempt is logged, and a successful recovery signs the owner out everywhere. The questions
 * shown for an email that has no recovery are decoys, so the screen cannot be used to find out which emails exist.
 */
@Service
public class RecoveryService {

    /** Shown for emails with no recovery set up. A mix of Italian and English, so language gives nothing away. */
    private static final List<String> DECOYS = List.of(
            "What was the name of your first pet?", "In which city did your parents meet?",
            "What was your childhood nickname?", "What was the first car you drove?",
            "Come si chiamava la tua prima scuola?", "Qual è il tuo piatto preferito da bambino?",
            "Come si chiamava il tuo migliore amico d'infanzia?", "In che città hai fatto il tuo primo viaggio?",
            "What was the street you grew up on?", "Qual è il soprannome che ti davano da piccolo?");

    private final RecoveryRepository repository;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final RecoveryThrottle throttle;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    /** Checked against when the email has no recovery, so "no such owner" takes as long as "wrong answer". */
    private final String dummyHash;

    RecoveryService(RecoveryRepository repository, UserRepository users, PasswordEncoder encoder, AuthService auth,
                    RecoveryThrottle throttle, AuditLog audit, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.users = users;
        this.encoder = encoder;
        this.auth = auth;
        this.throttle = throttle;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.dummyHash = encoder.encode("not-a-real-answer");
    }

    /** What an owner has set up (questions only, never answers). */
    public Optional<Questions> statusOf(CurrentUser me) {
        return repository.questionsOf(me.restaurantId(), me.userId());
    }

    public void setUp(CurrentUser me, String currentPassword, String question1, String answer1, String question2, String answer2) {
        if (me.role() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the owner can set up account recovery");
        }
        AppUser owner = users.findById(me.userId()).orElseThrow(() -> ApiException.unauthorized("Not signed in"));
        // Anyone sitting at an unlocked, signed-in computer must still know the password to change how it is recovered.
        if (currentPassword == null || !encoder.matches(currentPassword, owner.passwordHash())) {
            throw ApiException.invalid("currentPassword", "The current password is not correct");
        }
        String q1 = question1 == null ? "" : question1.trim();
        String q2 = question2 == null ? "" : question2.trim();
        if (q1.length() < 8 || q1.length() > 200) {
            throw ApiException.invalid("question1", "Write a question of 8 to 200 characters");
        }
        if (q2.length() < 8 || q2.length() > 200) {
            throw ApiException.invalid("question2", "Write a question of 8 to 200 characters");
        }
        if (q1.equalsIgnoreCase(q2)) {
            throw ApiException.invalid("question2", "The two questions must be different");
        }
        String n1 = RecoveryAnswers.normalize(answer1);
        String n2 = RecoveryAnswers.normalize(answer2);
        if (n1.length() < RecoveryAnswers.MIN_LENGTH) {
            throw ApiException.invalid("answer1", "The answer needs at least 3 letters or digits");
        }
        if (n2.length() < RecoveryAnswers.MIN_LENGTH) {
            throw ApiException.invalid("answer2", "The answer needs at least 3 letters or digits");
        }
        if (n1.equals(n2)) {
            throw ApiException.invalid("answer2", "The two answers must be different");
        }
        repository.save(me.restaurantId(), me.userId(), q1, encoder.encode(n1), q2, encoder.encode(n2));
        audit.record("RECOVERY_SET_UP", "user", me.userId().toString(), Map.of());
    }

    /** The two questions for an email: the real ones, or decoys that look the same. */
    public List<String> questionsFor(String email) {
        String key = AuthService.normalizeEmail(email);
        return repository.findByEmail(key)
                .map(s -> List.of(s.question1(), s.question2()))
                .orElseGet(() -> {
                    int start = Math.floorMod(key.hashCode(), DECOYS.size());
                    return List.of(DECOYS.get(start), DECOYS.get((start + 3) % DECOYS.size()));
                });
    }

    public void reset(String email, String answer1, String answer2, String newPassword, String address) {
        String key = AuthService.normalizeEmail(email);
        PasswordPolicy.check(newPassword, "newPassword");
        throttle.checkAllowed(key, address);

        Optional<Stored> stored = repository.findByEmail(key);
        // Both answers are always checked, whatever the first result, so timing does not leak which one was wrong.
        boolean first = encoder.matches(RecoveryAnswers.normalize(answer1), stored.map(Stored::answer1Hash).orElse(dummyHash));
        boolean second = encoder.matches(RecoveryAnswers.normalize(answer2), stored.map(Stored::answer2Hash).orElse(dummyHash));

        if (stored.isEmpty() || !first || !second) {
            throttle.recordFailure(key, address);
            if (stored.isPresent()) {
                audit.recordFor(stored.get().restaurantId(), stored.get().userId(), key, "RECOVERY_FAILED", "user",
                        stored.get().userId().toString(), "FAILURE", Map.of());
            } else {
                audit.recordUnscoped(key, "RECOVERY_FAILED", "FAILURE", Map.of("reason", "no recovery for this email"));
            }
            throw ApiException.unauthorized("The answers do not match");
        }

        Stored owner = stored.get();
        String hash = encoder.encode(newPassword);
        TenantContext.callAs(owner.restaurantId(), () -> tx.execute(status -> {
            users.updatePassword(owner.restaurantId(), owner.userId(), hash);
            auth.revokeAllSessions(owner.userId());
            return null;
        }));
        throttle.clear(key);
        audit.recordFor(owner.restaurantId(), owner.userId(), key, "RECOVERY_SUCCEEDED", "user", owner.userId().toString(),
                "SUCCESS", Map.of());
    }
}
