package com.checkon.member.integration.learning;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 승우님의 {@code learning_records}(V8) 에 학생이 만든 학습 원본을 <b>SQL 로만</b> 기록한다.
 * PR2 의 {@code learning_records_member_student_insert}(V38:230-234) 가 격리한다.
 *
 * <p>🔴 <b>problem 패키지의 Entity/Repository 를 import 하지 않는다</b>(PR5 §8 · G2 규칙).
 * 승우님 도메인 계층에 의존이 생기는 순간 승우님이 그것을 손대면 이 어댑터가 조용히 깨진다.</p>
 *
 * <p>🔴 <b>{@code class_group_id} 는 항상 NULL 이다.</b> non-null 이면
 * {@code fk_learning_records_class_teacher}(V8:27-29)가 {@code class_groups(id, teacher_id)}
 * 를 참조하는데 {@code class_groups} 는 RLS 가 켜져 있고(§0 전수 #8) 학생 SELECT 정책이 없어
 * FK 검증이 실패한다. "반 정보를 채우면 더 좋을 것 같아서" 채우는 순간 제출 전체가 죽는다.</p>
 *
 * <p>🔴 <b>{@code area_tag}/{@code type_tag} 는 NULL 로 남긴다.</b> 저장 어휘가
 * {@code learning_records} 와 {@code problem_assignment_responses} 사이에 어긋난다 —
 * {@code CheckOn-AI contracts/taxonomy.py} 는 소문자를 쓰지만 V17 의 대문자 4종을 그대로 흘리면
 * V33 의 새 CHECK 를 나중에 깬다. 변환 표는 만들지 않는다(1:1 대응 미확인).</p>
 *
 * <p>🔴 ✅ <b>MB-28 CONFIRMED · 둘 다 쓴다</b>(2026-08-26 · 승우님 확인 + 스키마 실측). 두
 * 테이블은 <b>대체 불가</b>다 — 「중복이니 하나 지우자」로 결론 내지 마라.
 * <ul>
 *   <li>{@code learning_records}(V8) — <b>사건 단위</b>({@code record_type ∈ {SOLVE, SUBMIT}}).
 *       {@code item_id}·{@code chosen_no} 컬럼이 <b>아예 없다</b>. {@code correct} 는 nullable.
 *       강사 <b>위험탐지</b>가 여기를 읽는다.</li>
 *   <li>{@code problem_assignment_responses}(V34) — <b>문항 단위</b>. {@code record_type} 이 없다.
 *       {@code area_tag}·{@code type_tag}·{@code skill_node_id} 가 전부 NOT NULL(정규식·enum
 *       CHECK). {@code correct = (chosen_no = correct_no)} CHECK. 오답이면
 *       {@code misconception_tag} 필수. 학생 <b>진단</b>이 여기를 읽는다.</li>
 * </ul>
 * 한쪽만으로는 위험탐지도 진단도 만들 수 없다. 제출 트랜잭션이 <b>둘 다</b> 한 트랜잭션에서
 * 쓴다. 이 사실을 근거 없이 뒤집지 마라 — 뒤집으려면 스키마부터 바꿔야 한다.</p>
 */
@Component
public class LearningRecordWriter {

	/** 문항 한 개당 한 행. attempt 당 문항 수 만큼 배치로 넣는다. */
	private static final String INSERT_SOLVE = """
		INSERT INTO learning_records
			(id, teacher_id, student_id, class_group_id, record_type, occurred_at,
			 source_type, external_record_ref, correct, duration_sec, area_tag, type_tag,
			 item_format, assignment_title_text, created_at, updated_at)
		VALUES (?, ?, ?, NULL, 'SOLVE', ?, 'member_attempt_item', ?, ?, ?, NULL, NULL,
		        'mcq', ?, ?, ?)
		""";

	/** attempt 한 건당 한 행. 세트 요약 — {@code correct}·{@code area_tag}·{@code type_tag} 는 NULL. */
	private static final String INSERT_SUBMIT = """
		INSERT INTO learning_records
			(id, teacher_id, student_id, class_group_id, record_type, occurred_at,
			 source_type, external_record_ref, correct, duration_sec, area_tag, type_tag,
			 item_format, assignment_title_text, created_at, updated_at)
		VALUES (?, ?, ?, NULL, 'SUBMIT', ?, 'member_attempt', ?, NULL, ?, NULL, NULL,
		        NULL, ?, ?, ?)
		""";

	private final JdbcTemplate jdbcTemplate;

	public LearningRecordWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * {@code SOLVE} 행 전량 + {@code SUBMIT} 행 1건을 같은 트랜잭션에서 넣는다. 🔴 부분 성공을
	 * 허용하지 않는다 — 채점만 되고 원본이 안 남는 상태는 회복할 방법이 없다(V8:52-55 주석,
	 * {@code external_record_ref} 는 <b>의도적으로 unique 가 아니다</b>).
	 */
	public UUID writeAll(
		UUID attemptId,
		UUID teacherId,
		UUID studentId,
		Instant occurredAt,
		String assignmentTitle,
		int totalActiveElapsedSec,
		List<SolveRow> solves
	) {
		insertSolves(attemptId, teacherId, studentId, occurredAt, assignmentTitle, solves);
		return insertSubmit(attemptId, teacherId, studentId, occurredAt, assignmentTitle,
			totalActiveElapsedSec);
	}

	private void insertSolves(
		UUID attemptId, UUID teacherId, UUID studentId, Instant occurredAt,
		String assignmentTitle, List<SolveRow> rows
	) {
		if (rows.isEmpty()) {
			return;
		}
		OffsetDateTime timestamp = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
		jdbcTemplate.batchUpdate(INSERT_SOLVE, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
				SolveRow row = rows.get(i);
				ps.setObject(1, UUID.randomUUID());
				ps.setObject(2, teacherId);
				ps.setObject(3, studentId);
				ps.setObject(4, timestamp);
				// 🔴 external_record_ref = "<attemptId>:<itemId>" — 자연 결합키. unique 는 아니다.
				ps.setString(5, attemptId + ":" + row.itemId());
				ps.setObject(6, row.correct());
				ps.setObject(7, row.activeElapsedSec());
				ps.setString(8, assignmentTitle);
				ps.setObject(9, timestamp);
				ps.setObject(10, timestamp);
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	private UUID insertSubmit(
		UUID attemptId, UUID teacherId, UUID studentId, Instant occurredAt,
		String assignmentTitle, int totalActiveElapsedSec
	) {
		UUID id = UUID.randomUUID();
		OffsetDateTime timestamp = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
		jdbcTemplate.update(INSERT_SUBMIT,
			id, teacherId, studentId, timestamp,
			attemptId.toString(), totalActiveElapsedSec,
			assignmentTitle, timestamp, timestamp);
		return id;
	}

	/**
	 * {@code SOLVE} 행 하나에 담을 값. 미응답 문항은 이 리스트에 포함하지 않는다
	 * ({@code correct} 를 채울 자연스러운 값이 없다).
	 */
	public record SolveRow(
		UUID itemId,
		Boolean correct,
		Integer activeElapsedSec
	) {
	}
}
