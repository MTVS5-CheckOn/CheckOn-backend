package com.checkon.engagement.domain;
import static org.assertj.core.api.Assertions.*;import java.time.Instant;import java.util.UUID;import org.junit.jupiter.api.Test;
class EngagementDomainTest {
 private static final Instant NOW=Instant.parse("2026-08-04T00:00:00Z");
 @Test void pendingAlertCanBeApprovedIdempotentlyButCannotBeRejectedAfterward(){EngagementAlert a=EngagementAlert.pending(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),NOW);a.approve(NOW.plusSeconds(1));a.approve(NOW.plusSeconds(2));assertThat(a.status()).isEqualTo(AlertStatus.APPROVED);assertThatThrownBy(()->a.reject("오경보",NOW.plusSeconds(3))).isInstanceOf(IllegalStateException.class);}
 @Test void pendingAlertCanBeRejectedButRequiresReasonAndCannotBeApprovedAfterward(){EngagementAlert a=EngagementAlert.pending(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),NOW);assertThatThrownBy(()->a.reject(" ",NOW)).isInstanceOf(IllegalArgumentException.class);a.reject("해당 없음",NOW.plusSeconds(1));assertThat(a.status()).isEqualTo(AlertStatus.REJECTED);assertThatThrownBy(()->a.approve(NOW.plusSeconds(2))).isInstanceOf(IllegalStateException.class);}
 @Test void reminderFinalStateCannotBeChanged(){InterventionReminder r=InterventionReminder.create(UUID.randomUUID(),UUID.randomUUID(),NOW.plusSeconds(100),NOW);r.complete(NOW.plusSeconds(1));assertThat(r.status()).isEqualTo(ReminderStatus.COMPLETED);assertThatThrownBy(()->r.cancel(NOW.plusSeconds(2))).isInstanceOf(IllegalStateException.class);}
}
