package com.checkon.member.notification.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkon.member.common.error.FieldViolation;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberException;
import com.checkon.member.common.persistence.MemberDatabaseContext;
import com.checkon.member.common.presentation.CursorPage;
import com.checkon.member.common.security.MemberSubject;
import com.checkon.member.learning.application.WorksheetCursor;
import com.checkon.member.learning.application.WorksheetCursor.InvalidWorksheetCursorException;
import com.checkon.member.notification.application.dto.NotificationResponse;
import com.checkon.member.notification.infrastructure.persistence.MemberNotificationRepository;

/**
 * 학부모 알림 API — 목록·개별 읽음·전체 읽음.
 *
 * <p>🔴 계정 컨텍스트만 연다. recipient self policy 로 격리한다.</p>
 *
 * <p>🔴 {@code learning} sub-context 의 {@link WorksheetCursor} 를 재사용한다 —
 * 두 번째 인코더를 만들지 않기 위함이다(PR6 §3).</p>
 */
@Service
public class ParentNotificationService {

	private static final int LIMIT_MAX = 50;

	private final MemberNotificationRepository notificationRepository;
	private final MemberDatabaseContext databaseContext;
	private final Clock clock;

	public ParentNotificationService(
		MemberNotificationRepository notificationRepository,
		MemberDatabaseContext databaseContext,
		Clock clock
	) {
		this.notificationRepository = notificationRepository;
		this.databaseContext = databaseContext;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public CursorPage<NotificationResponse> list(
		MemberSubject subject, String cursor, Integer limit
	) {
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(subject.requireParentProfileId());

		int pageSize = requireValidLimit(limit);
		OffsetDateTime cursorAt = null;
		UUID cursorId = null;
		if (cursor != null && !cursor.isBlank()) {
			WorksheetCursor decoded = decodeCursor(cursor);
			cursorAt = OffsetDateTime.ofInstant(decoded.publishedAt(), ZoneOffset.UTC);
			cursorId = decoded.assignmentId();
		}

		List<NotificationResponse> rows = notificationRepository.findPage(
			subject.accountId(), cursorAt, cursorId, pageSize + 1);
		boolean hasNext = rows.size() > pageSize;
		List<NotificationResponse> pageRows = hasNext ? rows.subList(0, pageSize) : rows;
		String nextCursor = null;
		if (hasNext) {
			NotificationResponse last = pageRows.get(pageRows.size() - 1);
			nextCursor = new WorksheetCursor(last.createdAt(), last.notificationId()).encode();
		}
		return new CursorPage<>(pageRows, nextCursor, hasNext);
	}

	@Transactional
	public void markRead(MemberSubject subject, UUID notificationId) {
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(subject.requireParentProfileId());

		// 🔴 부재는 404 (남의 알림도 recipient 정책으로 0행이라 404 로 갈린다).
		if (!notificationRepository.existsForRecipient(notificationId, subject.accountId())) {
			throw new MemberException(MemberErrorCode.RESOURCE_NOT_FOUND,
				"no notification matches the given id");
		}
		// 🔴 이미 읽었으면 UPDATE 는 0행이지만 응답은 여전히 204 (멱등).
		notificationRepository.markRead(notificationId, subject.accountId(), clock.instant());
	}

	@Transactional
	public void markAllRead(MemberSubject subject) {
		databaseContext.setCurrentAccount(subject.accountId());
		databaseContext.setCurrentParent(subject.requireParentProfileId());
		notificationRepository.markAllRead(subject.accountId(), clock.instant());
	}

	private int requireValidLimit(Integer limit) {
		int pageSize = limit == null ? LIMIT_MAX : limit;
		if (pageSize < 1 || pageSize > LIMIT_MAX) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"limit must be between 1 and 50",
				List.of(new FieldViolation("limit", "limit must be between 1 and 50")));
		}
		return pageSize;
	}

	private WorksheetCursor decodeCursor(String cursor) {
		try {
			return WorksheetCursor.decode(cursor);
		}
		catch (InvalidWorksheetCursorException exception) {
			throw new MemberException(MemberErrorCode.INVALID_REQUEST,
				"cursor is malformed",
				List.of(new FieldViolation("cursor", "cursor is malformed")));
		}
	}
}
