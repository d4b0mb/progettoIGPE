package it.ristomanager.cloud.recovery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two questions shown for an email that has no recovery set up. They look like a real answer and are the same
 * every time for the same email, but which two an email gets depends on a server secret (HMAC-SHA256), so nobody can
 * work them out in advance or compare them with another server.
 */
final class RecoveryDecoys {

    /** A mix of Italian and English, so language gives nothing away. */
    static final List<String> POOL = List.of(
            "What was the name of your first pet?", "In which city did your parents meet?",
            "What was your childhood nickname?", "What was the first car you drove?",
            "Come si chiamava la tua prima scuola?", "Qual è il tuo piatto preferito da bambino?",
            "Come si chiamava il tuo migliore amico d'infanzia?", "In che città hai fatto il tuo primo viaggio?",
            "What was the street you grew up on?", "Qual è il soprannome che ti davano da piccolo?",
            "What was the name of your first teacher?", "Which book did you love as a child?",
            "Come si chiamava il tuo vicino di casa da bambino?", "Qual era il tuo giocattolo preferito da piccolo?",
            "What was the make of your first bicycle?", "Come si chiamava il tuo primo insegnante di musica?");

    private static final String ALGORITHM = "HmacSHA256";
    /** Separates this use of the secret from any other (the secret is shared with the token signer). */
    private static final byte[] PURPOSE = "ristomanager/recovery-decoys/v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;

    /** {@code secret} must not be empty (the application's signing key is at least 32 bytes). */
    RecoveryDecoys(byte[] secret) {
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(secret, ALGORITHM), PURPOSE), ALGORITHM);
    }

    /** Two different questions from the pool, always the same two for the same normalised email. */
    List<String> forEmail(String normalizedEmail) {
        byte[] mac = hmac(key, normalizedEmail.getBytes(StandardCharsets.UTF_8));
        int n = POOL.size();
        int first = (int) (unsigned(mac, 0) % n);
        int second = (int) (unsigned(mac, 4) % (n - 1));
        if (second >= first) {
            second++;
        }
        return List.of(POOL.get(first), POOL.get(second));
    }

    private static long unsigned(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFFL) << 24) | ((bytes[offset + 1] & 0xFFL) << 16)
                | ((bytes[offset + 2] & 0xFFL) << 8) | (bytes[offset + 3] & 0xFFL);
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
