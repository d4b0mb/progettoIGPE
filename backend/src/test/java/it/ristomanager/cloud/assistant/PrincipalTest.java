package it.ristomanager.cloud.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.user.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrincipalTest {

    private static final UUID RID = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void aStaffPrincipalCarriesRestaurantUserAndRole() {
        Principal p = Principal.staff(RID, USER, Role.WAITER);

        assertThat(p.isStaff()).isTrue();
        assertThat(p.isGuest()).isFalse();
        assertThat(p.restaurantId()).isEqualTo(RID);
        assertThat(p.userId()).isEqualTo(USER);
        assertThat(p.role()).isEqualTo(Role.WAITER);
        assertThat(p.auditLabel()).isEqualTo("staff:WAITER");
        assertThatThrownBy(p::channel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(p::subjectId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStaffPrincipalNeedsAllThreeParts() {
        assertThatThrownBy(() -> Principal.staff(null, USER, Role.OWNER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Principal.staff(RID, null, Role.OWNER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Principal.staff(RID, USER, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGuestPrincipalHasNoUserAndNoRole() {
        Principal p = Principal.guest(RID, "telegram", "123456789");

        assertThat(p.isGuest()).isTrue();
        assertThat(p.isStaff()).isFalse();
        assertThat(p.restaurantId()).isEqualTo(RID);
        assertThat(p.channel()).isEqualTo("telegram");
        assertThat(p.subjectId()).isEqualTo("123456789");
        assertThatThrownBy(p::userId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(p::role).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyKnownChannelIsAcceptedAndNormalised() {
        for (String channel : new String[] {"telegram", "whatsapp", "phone", "web"}) {
            assertThat(Principal.guest(RID, channel, "x").auditLabel()).isEqualTo("guest:" + channel);
        }
        assertThat(Principal.guest(RID, "  WhatsApp ", "+39 333 111 2222").auditLabel()).isEqualTo("guest:whatsapp");
    }

    @Test
    void anUnknownChannelIsRefused() {
        for (String channel : new String[] {"sms", "email", "", "  ", "telegram2", "staff", null}) {
            assertThatThrownBy(() -> Principal.guest(RID, channel, "123")).as(String.valueOf(channel))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theSubjectIdIsRequiredAndSane() {
        assertThatThrownBy(() -> Principal.guest(RID, "telegram", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Principal.guest(RID, "telegram", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Principal.guest(RID, "telegram", "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Principal.guest(RID, "telegram", "x".repeat(Principal.SUBJECT_MAX + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Principal.guest(RID, "telegram", "12\n34")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Principal.guest(null, "telegram", "123")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neitherTheAuditLabelNorToStringNorTheGateKeyShowTheSubjectId() {
        String subject = "+393331112222";
        Principal p = Principal.guest(RID, "whatsapp", subject);

        assertThat(p.auditLabel()).isEqualTo("guest:whatsapp").doesNotContain(subject).doesNotContain("3331112222");
        assertThat(p.toString()).doesNotContain(subject).doesNotContain("3331112222");
        assertThat(p.gateKey().toString()).doesNotContain("3331112222");
    }

    @Test
    void theGateKeyIsStablePerSenderAndDifferentAcrossSendersChannelsAndRestaurants() {
        UUID key = Principal.guest(RID, "telegram", "111").gateKey();

        assertThat(Principal.guest(RID, "telegram", "111").gateKey()).isEqualTo(key);
        assertThat(Principal.guest(RID, "telegram", "222").gateKey()).isNotEqualTo(key);
        assertThat(Principal.guest(RID, "whatsapp", "111").gateKey()).isNotEqualTo(key);
        assertThat(Principal.guest(UUID.randomUUID(), "telegram", "111").gateKey()).isNotEqualTo(key);
        assertThat(Principal.staff(RID, USER, Role.OWNER).gateKey()).isEqualTo(USER);
    }
}
