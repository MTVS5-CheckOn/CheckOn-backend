package com.checkon.engagement.domain;
import static org.assertj.core.api.Assertions.*;
import java.time.*;import java.util.UUID;import org.junit.jupiter.api.Test;
class AlertFollowUpTodoTest {
 static final Instant CREATED=Instant.parse("2026-08-04T15:00:00Z");
 @Test void startsOpenAndCompletionIsIdempotent(){AlertFollowUpTodo t=AlertFollowUpTodo.create(UUID.randomUUID(),UUID.randomUUID(),"검토",LocalDate.of(2026,8,5),CREATED);assertThat(t.status()).isEqualTo(TodoStatus.OPEN);Instant done=CREATED.plusSeconds(10);t.complete(done);t.complete(CREATED.plusSeconds(20));assertThat(t.status()).isEqualTo(TodoStatus.DONE);assertThat(t.completedAt()).isEqualTo(done);}
 @Test void rejectsBlankTextAndEarlyCompletion(){assertThatThrownBy(()->AlertFollowUpTodo.create(UUID.randomUUID(),UUID.randomUUID()," ",LocalDate.now(),CREATED)).isInstanceOf(IllegalArgumentException.class);AlertFollowUpTodo t=AlertFollowUpTodo.create(UUID.randomUUID(),UUID.randomUUID(),"검토",LocalDate.of(2026,8,5),CREATED);assertThatThrownBy(()->t.complete(CREATED.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);}
}
