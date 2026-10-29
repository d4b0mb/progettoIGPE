package it.ristomanager.cloud.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryDecoysTest {

    private static byte[] secret(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private final RecoveryDecoys decoys = new RecoveryDecoys(secret("a-test-secret-that-is-at-least-32-bytes-long"));

    @Test
    void theSameEmailAlwaysGetsTheSameTwoQuestions() {
        List<String> first = decoys.forEmail("nobody@example.com");
        for (int i = 0; i < 50; i++) {
            assertThat(decoys.forEmail("nobody@example.com")).isEqualTo(first);
        }
        // A second instance with the same secret (a restart) agrees too.
        assertThat(new RecoveryDecoys(secret("a-test-secret-that-is-at-least-32-bytes-long")).forEmail("nobody@example.com"))
                .isEqualTo(first);
    }

    @Test
    void theShapeIsTwoDifferentQuestionsFromThePool() {
        for (int i = 0; i < 2_000; i++) {
            List<String> questions = decoys.forEmail("person-" + i + "@example.com");
            assertThat(questions).hasSize(2);
            assertThat(questions.get(0)).isNotEqualTo(questions.get(1));
            assertThat(RecoveryDecoys.POOL).containsAll(questions);
            // Same length rules as questions a real owner can save (8 to 200 characters).
            assertThat(questions).allSatisfy(q -> assertThat(q.length()).isBetween(8, 200));
        }
    }

    @Test
    void differentEmailsMostlyGetDifferentQuestions() {
        Set<List<String>> pairs = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            pairs.add(decoys.forEmail("person-" + i + "@example.com"));
        }
        // 16 questions give 240 ordered pairs; 500 emails should hit well over half of them.
        assertThat(pairs.size()).isGreaterThan(120);
        // And one pair must not dominate.
        long same = 0;
        List<String> reference = decoys.forEmail("person-0@example.com");
        for (int i = 0; i < 500; i++) {
            if (decoys.forEmail("person-" + i + "@example.com").equals(reference)) {
                same++;
            }
        }
        assertThat(same).isLessThan(20);
    }

    @Test
    void theChoiceDependsOnTheServerSecretSoItCannotBePredicted() {
        RecoveryDecoys other = new RecoveryDecoys(secret("another-secret-that-is-also-32-bytes-or-more"));
        int different = 0;
        for (int i = 0; i < 200; i++) {
            String email = "person-" + i + "@example.com";
            if (!decoys.forEmail(email).equals(other.forEmail(email))) {
                different++;
            }
        }
        assertThat(different).isGreaterThan(150);
    }

    @Test
    void theChoiceIsNotTheOldPredictableHashCodeRule() {
        // The first version picked start = hashCode mod 10 and the pair (start, start + 3). That is no longer the rule.
        int matchesOldRule = 0;
        for (int i = 0; i < 200; i++) {
            String email = "person-" + i + "@example.com";
            int start = Math.floorMod(email.hashCode(), 10);
            List<String> old = List.of(RecoveryDecoys.POOL.get(start), RecoveryDecoys.POOL.get((start + 3) % 10));
            if (decoys.forEmail(email).equals(old)) {
                matchesOldRule++;
            }
        }
        assertThat(matchesOldRule).isLessThan(10);
    }
}
