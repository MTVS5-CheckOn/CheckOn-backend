package com.checkon.publication.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.checkon.global.persistence.TeacherTenantDatabaseContext;
import com.checkon.publication.application.dto.ConsultationAnswerResult;
import com.checkon.publication.application.dto.TeacherConsultationDetail;
import com.checkon.publication.application.dto.TeacherConsultationMessage;
import com.checkon.publication.application.dto.TeacherConsultationSummary;
import com.checkon.publication.domain.ConsultationStatus;
import com.checkon.publication.domain.PublicationException;
import com.checkon.publication.domain.TeacherConsultationMessageRow;
import com.checkon.publication.domain.TeacherConsultationRow;
import com.checkon.publication.infrastructure.TeacherConsultationRepository;

/**
 * 강사의 상담 조회와 <b>답변 발행</b>.
 *
 * <p>🔴 PR8(V44)이 학부모 접수·조회를 만들었지만 <b>답변을 발행하는 쪽이 없었다.</b>
 * 학부모가 상담을 넣으면 저장은 되는데 답이 영영 오지 않았다. 이 서비스가 그 경로를 연다 —
 * 🔴 <b>AI 없이 강사가 직접 써도 제품은 돈다.</b> 막고 있던 것은 AI 가 아니라 발행 경로였다.</p>
 *
 * <p>🔴 <b>강사 컨텍스트만 연다.</b> V45·V44 정책은 전부 {@code PERMISSIVE} 라 PostgreSQL 이
 * OR 로 합친다 — 한 트랜잭션에서 학부모 컨텍스트까지 열면 격리가 무너진다.
 * {@code PublicationContextRuleTest} 가 그것을 게이트로 막는다.</p>
 *
 * <p>🔴 <b>멱등은 상태로 본다.</b> {@code Idempotency-Key} 를 쓰지 않은 이유:
 * ① 키를 저장할 테이블이 없고 이 작업은 마이그레이션 0개다 ② member 의
 * {@code IdempotencyGuard} 는 member 패키지라 경계를 넘어 가져다 쓸 수 없다
 * ③ 무엇보다 <b>상태 전이 자체가 두 번째 쓰기를 불가능하게 만든다</b> — 첫 답변이
 * {@code ANSWERED} 로 올리고, 두 번째 호출은 {@code FOR UPDATE} 로 잠근 뒤 그 상태를 보고
 * <b>409</b> 를 낸다. 행은 한 벌뿐이다.
 * ⚠ 대가: 네트워크 타임아웃 뒤 재시도한 클라이언트는 {@code 201} 이 아니라 {@code 409} 를
 * 받는다 — 「내 답이 갔는지」를 응답만으로 구분하지 못하고 상세 조회로 확인해야 한다(MB-65).</p>
 *
 * <p>🔴 <b>학부모 알림을 넣지 않았다.</b> {@code member_notifications} 의 INSERT 정책이
 * {@code current_checkon_account_id() IS NOT NULL} 을 요구하는데(V41 실측), 강사 컨텍스트만
 * 연 트랜잭션에서 그 값은 {@code NULL} 이라 <b>RLS 가 거절한다.</b> 계정 컨텍스트를 열면
 * 게이트 위반이고, 상담용 outbox 테이블은 없다(V44 는 2테이블뿐). <b>정책 없이 우회하지 않고</b>
 * 안건으로 남겼다 — MB-66.</p>
 *
 * <p>🔴 <b>로그에 상담 원문·학생 이름을 넣지 않는다.</b> 식별자와 상태만 남긴다.</p>
 */
@Service
public class ConsultationAnswerService {

	private static final Logger log = LoggerFactory.getLogger(ConsultationAnswerService.class);
	private static final int MAX_CONTENT_LENGTH = 2000;
	private static final int MAX_LIMIT = 50;
	private static final int DEFAULT_LIMIT = 20;

	private final TeacherConsultationRepository consultations;
	private final TeacherTenantDatabaseContext tenantContext;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;
	private final TransactionTemplate readOnlyTransactionTemplate;

	public ConsultationAnswerService(
		TeacherConsultationRepository consultations,
		TeacherTenantDatabaseContext tenantContext,
		Clock clock,
		PlatformTransactionManager transactionManager
	) {
		this.consultations = consultations;
		this.tenantContext = tenantContext;
		this.clock = clock;
		// 🔴 @Transactional 을 쓰지 않는다. 같은 빈의 메서드를 부르면 자기 호출이라 프록시를
		//    통과하지 못해 어노테이션이 통째로 무시된다(PR7 실측: 그 자리에서 500 이 났다).
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.transactionTemplate.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
		this.readOnlyTransactionTemplate.setPropagationBehavior(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readOnlyTransactionTemplate.setReadOnly(true);
	}

	public List<TeacherConsultationSummary> list(
		UUID teacherId, String status, String cursor, Integer limit
	) {
		int safeLimit = validatedLimit(limit);
		String safeStatus = validatedStatus(status);
		Cursor decoded = Cursor.decode(cursor);
		List<TeacherConsultationSummary> found = readOnlyTransactionTemplate.execute(tx -> {
			tenantContext.setCurrentTeacher(teacherId);
			return consultations.findPage(teacherId, safeStatus,
				decoded == null ? null : decoded.createdAt(),
				decoded == null ? null : decoded.id(), safeLimit).stream()
				.map(ConsultationAnswerService::toSummary).toList();
		});
		return found == null ? List.of() : found;
	}

	public TeacherConsultationDetail get(UUID teacherId, UUID consultationId) {
		TeacherConsultationDetail detail = readOnlyTransactionTemplate.execute(tx -> {
			tenantContext.setCurrentTeacher(teacherId);
			TeacherConsultationRow row = consultations.find(consultationId, teacherId)
				.orElseThrow(ConsultationAnswerService::notFound);
			return toDetail(row, consultations.findMessages(consultationId, teacherId));
		});
		if (detail == null) {
			throw notFound();
		}
		return detail;
	}

	/**
	 * 답변을 발행한다. 🔴 <b>한 트랜잭션</b>이다 — 메시지와 상태가 함께 커밋되거나 함께 없다.
	 *
	 * <p>순서: 잠그고 읽기 → 상태 판정 → 메시지 INSERT({@code published_at} 을 쓰는 순간이
	 * 곧 발행) → 상담 UPDATE. 🔴 시각은 <b>한 번만</b> 뜬다.</p>
	 */
	public ConsultationAnswerResult publishAnswer(
		UUID teacherId, UUID consultationId, String content
	) {
		String body = validatedContent(content);
		ConsultationAnswerResult result = transactionTemplate.execute(tx -> {
			tenantContext.setCurrentTeacher(teacherId);
			// 🔴 잠그고 읽는다. 안 잠그면 「상태를 보고 → 쓰는」 사이에 다른 요청이 끼어
			//    답이 두 벌 나간다. SKIP LOCKED 는 쓰지 않는다 — 여기서는 기다렸다가
			//    갱신된 상태를 보고 409 를 내야 한다.
			TeacherConsultationRow row = consultations.lock(consultationId, teacherId)
				.orElseThrow(ConsultationAnswerService::notFound);
			if (!row.answerable()) {
				// 🔴 조용히 허용하지 않는다. 이미 답했거나 끝났거나 취소된 상담이다.
				throw PublicationException.conflict(
					"consultation is not answerable in status " + row.status());
			}
			Instant publishedAt = clock.instant();
			UUID messageId = consultations.insertTeacherMessage(row, body, publishedAt);
			int updated = consultations.markAnswered(consultationId, teacherId, publishedAt);
			if (updated != 1) {
				// 🔴 RLS 는 예외가 아니라 0행으로 거절한다. 안 보면 조용히 지나간다.
				throw new IllegalStateException(
					"consultation could not be marked answered: " + consultationId);
			}
			log.info("publication.consultation.answered consultation={} teacher={} message={}",
				consultationId, teacherId, messageId);
			return new ConsultationAnswerResult(
				consultationId, messageId, ConsultationStatus.ANSWERED, publishedAt);
		});
		if (result == null) {
			throw new IllegalStateException("answer transaction returned nothing");
		}
		return result;
	}

	private static TeacherConsultationSummary toSummary(TeacherConsultationRow row) {
		return new TeacherConsultationSummary(row.consultationId(), row.studentId(),
			row.status(), row.topic(), row.urgency(), row.createdAt(), row.answeredAt(),
			row.answerable());
	}

	private static TeacherConsultationDetail toDetail(
		TeacherConsultationRow row, List<TeacherConsultationMessageRow> messages
	) {
		return new TeacherConsultationDetail(row.consultationId(), row.studentId(),
			row.parentId(), row.status(), row.aiStatus(), row.topic(), row.urgency(),
			row.content(), row.createdAt(), row.updatedAt(), row.answeredAt(),
			row.answerable(),
			messages.stream().map(message -> new TeacherConsultationMessage(
				message.messageId(), message.authorRole(), message.content(),
				message.publishedAt())).toList());
	}

	/** 🔴 길이 상한은 V44 CHECK 와 같은 2000자다. DB 까지 가서 터지기 전에 400 으로 낸다. */
	private static String validatedContent(String content) {
		if (content == null || content.isBlank()) {
			throw PublicationException.invalid("content is required");
		}
		String trimmed = content.strip();
		if (trimmed.length() > MAX_CONTENT_LENGTH) {
			throw PublicationException.invalid(
				"content must be at most " + MAX_CONTENT_LENGTH + " characters");
		}
		return trimmed;
	}

	/** 🔴 상한 초과는 조용히 깎지 않고 400 이다(member 목록 규약과 같다). */
	private static int validatedLimit(Integer limit) {
		int value = limit == null ? DEFAULT_LIMIT : limit;
		if (value < 1 || value > MAX_LIMIT) {
			throw PublicationException.invalid("limit must be between 1 and " + MAX_LIMIT);
		}
		return value;
	}

	/** 🔴 어휘 밖 상태는 400 이다. 모르는 값을 조용히 무시하면 필터가 안 걸린 채 전체가 나간다. */
	private static String validatedStatus(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		if (!List.of(ConsultationStatus.SUBMITTED, ConsultationStatus.REVIEWING,
			ConsultationStatus.ANSWERED, ConsultationStatus.CLOSED,
			ConsultationStatus.CANCELLED).contains(status)) {
			throw PublicationException.invalid("unknown consultation status: " + status);
		}
		return status;
	}

	private static PublicationException notFound() {
		// 🔴 남의 상담도 부재도 같은 404 다. 구분하면 존재 여부를 알려주는 셈이다.
		return PublicationException.notFound("consultation not found");
	}

	/** 목록 cursor. {@code base64url("<createdAt epochMilli>:<id>")}. */
	record Cursor(Instant createdAt, UUID id) {

		static Cursor decode(String cursor) {
			if (cursor == null || cursor.isBlank()) {
				return null;
			}
			try {
				String raw = new String(java.util.Base64.getUrlDecoder().decode(cursor),
					java.nio.charset.StandardCharsets.UTF_8);
				int mark = raw.indexOf(':');
				if (mark <= 0) {
					throw new IllegalArgumentException("cursor");
				}
				return new Cursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, mark))),
					UUID.fromString(raw.substring(mark + 1)));
			}
			catch (IllegalArgumentException malformed) {
				// 🔴 형식 오류는 400 이다. 조용히 첫 페이지로 되돌리지 않는다.
				throw PublicationException.invalid("cursor is malformed");
			}
		}
	}
}
