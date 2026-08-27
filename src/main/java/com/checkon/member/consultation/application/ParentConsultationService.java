package com.checkon.member.consultation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.IdempotencyGuard;
import com.checkon.member.common.persistence.IdempotentOutcome;
import com.checkon.member.common.persistence.IdempotentPayload;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.presentation.CursorPage;
import com.checkon.member.common.presentation.MemberResponse;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.consultation.application.dto.ConsultationDetailResponse;
import com.checkon.member.consultation.application.dto.ConsultationMessageResponse;
import com.checkon.member.consultation.application.dto.ConsultationResponse;
import com.checkon.member.consultation.application.dto.CreateConsultationRequest;
import com.checkon.member.consultation.domain.ConsultationAiStatus;
import com.checkon.member.consultation.domain.ConsultationRecord;
import com.checkon.member.consultation.domain.ConsultationStatus;
import com.checkon.member.consultation.infrastructure.persistence
	.ConsultationRelationshipRepository;
import com.checkon.member.consultation.infrastructure.persistence.MemberConsultationRepository;

@Service
public class ParentConsultationService {

	public static final String ROUTE_CREATE = "POST /member/parents/me/consultations";
	private static final int CREATED = 201;
	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 50;

	private final MemberConsultationRepository repository;
	private final ConsultationRelationshipRepository relationships;
	private final ConsultationTextMasker textMasker;
	private final IdempotencyGuard idempotencyGuard;
	private final MemberDatabaseContext databaseContext;
	private final Clock clock;

	public ParentConsultationService(
		MemberConsultationRepository repository,
		ConsultationRelationshipRepository relationships,
		ConsultationTextMasker textMasker,
		IdempotencyGuard idempotencyGuard,
		MemberDatabaseContext databaseContext,
		Clock clock
	) {
		this.repository = repository;
		this.relationships = relationships;
		this.textMasker = textMasker;
		this.idempotencyGuard = idempotencyGuard;
		this.databaseContext = databaseContext;
		this.clock = clock;
	}

	@Transactional
	public IdempotentOutcome create(
		MemberSubject subject, String idempotencyKey, String rawBody,
		CreateConsultationRequest request
	) {
		UUID parentId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentId);
		validate(request);
		return idempotencyGuard.execute(subject.accountId(), ROUTE_CREATE,
			idempotencyKey, rawBody,
			() -> new IdempotentPayload(CREATED,
				MemberResponse.of(insert(parentId, request))));
	}

	@Transactional(readOnly = true)
	public CursorPage<ConsultationResponse> list(
		MemberSubject subject, UUID studentId, String cursor, Integer limit
	) {
		UUID parentId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentId);
		int pageSize = validLimit(limit);
		ConsultationCursor decoded = decode(cursor);
		return databaseContext.withVerifiedChildScope(parentId, studentId,
			() -> fetchPage(parentId, studentId, decoded, pageSize));
	}

	@Transactional(readOnly = true)
	public ConsultationDetailResponse get(
		MemberSubject subject, UUID studentId, UUID consultationId
	) {
		UUID parentId = subject.requireParentProfileId();
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(parentId);
		return databaseContext.withVerifiedChildScope(parentId, studentId,
			() -> fetchDetail(studentId, consultationId));
	}

	private ConsultationResponse insert(UUID parentId, CreateConsultationRequest request) {
		return databaseContext.withVerifiedChildScope(parentId, request.studentId(), () -> {
			if (!relationships.isTeacherForStudent(request.teacherId(), request.studentId())) {
				throw new MemberException(MemberErrorCode.RELATIONSHIP_REQUIRED,
					"teacher is not assigned to this student");
			}
			validateContext(request.context(), request.studentId());
			Instant now = clock.instant();
			// 실명 원본은 teacher 전용 RLS라 학부모 트랜잭션에서 읽지 않는다.
			// 요청 시점에는 주민번호·전화·이메일 정규식만 적용하고 빈 실명 목록을 넘긴다.
			String masked = textMasker.mask(request.content(), List.of());
			CreateConsultationRequest.ConsultationContext context = request.context();
			ConsultationRecord row = new ConsultationRecord(
				null, parentId, request.studentId(), request.teacherId(),
				request.content(), masked, ConsultationStatus.SUBMITTED,
				ConsultationAiStatus.NOT_REQUESTED,
				context == null ? null : context.type(),
				context == null ? null : context.id(), null,
				now, now, null, null);
			UUID id = repository.insert(row);
			return toResponse(repository.findById(id, request.studentId())
				.orElseThrow(() -> new MemberException(MemberErrorCode.INTERNAL,
					"just-inserted consultation is unreadable")));
		});
	}

	private CursorPage<ConsultationResponse> fetchPage(
		UUID parentId, UUID studentId, ConsultationCursor cursor, int pageSize
	) {
		List<ConsultationRecord> rows = repository.findPage(
			parentId, studentId,
			cursor == null ? null : cursor.createdAt(),
			cursor == null ? null : cursor.consultationId(),
			pageSize + 1);
		boolean hasNext = rows.size() > pageSize;
		List<ConsultationRecord> page = hasNext ? rows.subList(0, pageSize) : rows;
		String nextCursor = null;
		if (hasNext) {
			ConsultationRecord last = page.get(page.size() - 1);
			nextCursor = new ConsultationCursor(last.createdAt(), last.id()).encode();
		}
		return new CursorPage<>(page.stream().map(ParentConsultationService::toResponse).toList(),
			nextCursor, hasNext);
	}

	private ConsultationDetailResponse fetchDetail(UUID studentId, UUID consultationId) {
		ConsultationRecord row = repository.findById(consultationId, studentId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"consultation not found"));
		List<ConsultationMessageResponse> messages = repository
			.findMessages(consultationId, studentId).stream()
			.map(message -> new ConsultationMessageResponse(
				message.id(), message.authorRole(), message.content(), message.publishedAt()))
			.toList();
		return new ConsultationDetailResponse(
			row.id(), row.studentId(), row.teacherId(), row.status(), row.createdAt(),
			row.updatedAt(), row.answeredAt(), row.aiStatus(), row.content(), messages);
	}

	private void validate(CreateConsultationRequest request) {
		if (request == null) {
			throw invalid("body", "request body is required");
		}
		if (request.studentId() == null) {
			throw invalid("studentId", "studentId is required");
		}
		if (request.teacherId() == null) {
			throw invalid("teacherId", "teacherId is required");
		}
		if (request.content() == null || request.content().isEmpty()
			|| request.content().length() > 2000) {
			throw invalid("content", "content length must be between 1 and 2000");
		}
		if (request.context() != null
			&& (request.context().type() == null || request.context().id() == null)) {
			throw invalid("context", "context type and id are both required");
		}
	}

	private void validateContext(
		CreateConsultationRequest.ConsultationContext context, UUID studentId
	) {
		if (context != null && !repository.contextExists(context.type(), context.id(), studentId)) {
			throw new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"consultation context not found");
		}
	}

	private static int validLimit(Integer limit) {
		int value = limit == null ? DEFAULT_LIMIT : limit;
		if (value < 1 || value > MAX_LIMIT) {
			throw invalid("limit", "limit must be between 1 and 50");
		}
		return value;
	}

	private static ConsultationCursor decode(String cursor) {
		if (cursor == null) {
			return null;
		}
		try {
			return ConsultationCursor.decode(cursor);
		}
		catch (ConsultationCursor.InvalidConsultationCursorException exception) {
			throw invalid("cursor", "cursor is malformed");
		}
	}

	private static ConsultationResponse toResponse(ConsultationRecord row) {
		return new ConsultationResponse(
			row.id(), row.studentId(), row.teacherId(), row.status(), row.createdAt(),
			row.updatedAt(), row.answeredAt(), row.aiStatus());
	}

	private static MemberException invalid(String field, String message) {
		return new MemberException(MemberErrorCode.INVALID_REQUEST, message,
			List.of(new FieldViolation(field, message)));
	}
}
