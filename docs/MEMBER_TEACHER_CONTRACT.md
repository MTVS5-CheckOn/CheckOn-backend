# MEMBER_TEACHER_CONTRACT — 강사 답변 기능 계약

> 🔴 이 문서만 읽고 강사 답변 기능을 만들 수 있어야 한다. 어긋난 것은 이 문서의 결함이다.
> 작성 2026-08-27 (PR6). 담당: member 경계 밖의 팀원.

---

## 0. 무엇을 위임하는가

PR6 (`feature/member/pr6-questions-profile-notifications`) 은 학생 질문 API 4개를 만들었지만
**강사 답변 API 는 만들지 않는다.** 그 이유는:

1. 강사 base path 는 `/api/v1/`(member 아님)이라 member 경계 밖이다.
2. 강사 컨텍스트 설정(`checkon.current_teacher_id`)은 member 절대 규칙 3 이 금지한다 — 학생 요청이
   남의 반 데이터를 보게 되는 최상위 불변식 위반이다.

이 문서는 강사 답변 기능을 만들 사람이 알아야 할 스키마·정책·상태 전이·트랜잭션 규약을 담는다.

## 1. 테이블 · 정책

### 1-1. `member_questions` (V41)

컬럼 (V41 원본 그대로):

```sql
id UUID PRIMARY KEY DEFAULT uuidv7(),
student_id UUID NOT NULL           -- REFERENCES student_profiles (id) ON DELETE RESTRICT
teacher_id UUID NOT NULL           -- REFERENCES teacher_profiles (id) ON DELETE RESTRICT
assignment_id UUID NOT NULL        -- REFERENCES problem_assignments (id) ON DELETE RESTRICT
attempt_id UUID                    -- REFERENCES member_attempts (id) ON DELETE RESTRICT (nullable)
item_id UUID                       -- (nullable)
title VARCHAR(200) NOT NULL
content TEXT NOT NULL
status VARCHAR(16) NOT NULL        -- CHECK IN ('WAITING','ANSWERED','FOLLOW_UP')
follow_up_count INTEGER NOT NULL DEFAULT 0
created_at TIMESTAMPTZ NOT NULL
answered_at TIMESTAMPTZ            -- CHECK (status = 'WAITING' OR answered_at IS NOT NULL)
```

CHECK 요약:
- `ck_member_questions_status` — status ∈ (WAITING, ANSWERED, FOLLOW_UP)
- `ck_member_questions_answered_at` — WAITING 이 아니면 answered_at 이 NOT NULL 이어야 한다
- `ck_member_questions_item_requires_attempt` — item_id 가 있으면 attempt_id 도 있어야 한다
- `ck_member_questions_follow_up_nonneg` — follow_up_count >= 0
- `ck_member_questions_title_length` — 1~200 자
- `ck_member_questions_content_length` — 1~2000 자

**강사 정책** (V41 §5-2):
- `member_questions_member_teacher_select` — `teacher_id = current_checkon_teacher_id()`
- `member_questions_member_teacher_update` — 같은 술어 (USING · WITH CHECK 둘 다)

🔴 이 두 정책은 이 PR 이 미리 넣어 두었다. 강사 답변 PR 이 정책을 다시 뽑아 쓰지 않아도 된다.

### 1-2. `member_question_messages` (V41)

```sql
id UUID PRIMARY KEY DEFAULT uuidv7(),
question_id UUID NOT NULL          -- REFERENCES member_questions (id) ON DELETE RESTRICT
author_role VARCHAR(8) NOT NULL    -- CHECK IN ('STUDENT', 'TEACHER')
author_account_id UUID NOT NULL    -- REFERENCES accounts (id) ON DELETE RESTRICT
content TEXT NOT NULL              -- CHECK (char_length BETWEEN 1 AND 2000)
published_at TIMESTAMPTZ NOT NULL
```

**강사 정책** (V41 §5-3):
- `member_question_messages_member_teacher_select` — EXISTS 자기 질문
- `member_question_messages_member_teacher_insert` — EXISTS 자기 질문 **AND** `author_role = 'TEACHER'`

🔴 **정책이 `author_role = 'TEACHER'` 를 강제한다.** 다른 값으로 INSERT 하면 정책이 거절한다.
애플리케이션이 이 컬럼을 잘못 채워도 DB 가 최종 보장한다.

## 2. 상태 전이

```
WAITING  ─(강사 답변)─→  ANSWERED  ─(학생 추가 질문)─→  FOLLOW_UP  ─(강사 재답변)─→  ANSWERED
```

🔴 **[확인 필요]** `answered_at` 갱신 규약이 미확정이다:
- 옵션 A: 첫 답변 시각으로 **고정** (재답변 시 갱신하지 않는다)
- 옵션 B: **최신 답변 시각**으로 매번 갱신

팀 확정 전에 구현하지 마라. `MEMBER_OPEN_ITEMS.md` 에 등재 후 결정한다.

전이 규약 (계약 §3 참조):
- 학생은 `WAITING` 에 message 를 추가할 수 없다 → `409` (PR6 §3 참조)
- 학생 `FOLLOW_UP` 추가는 상한이 있다 (`checkon.member.question.max-follow-ups`, 잠정 3)
- 강사 답변은 **한 트랜잭션 안에서**:
  1. `INSERT member_question_messages (author_role='TEACHER', author_account_id=강사_계정_id, ...)`
  2. `UPDATE member_questions SET status='ANSWERED', answered_at=? WHERE id=?`

🔴 순서를 바꾸지 마라. UPDATE 를 먼저 하면 message 없이 상태만 바뀌는 순간이 생긴다.

## 3. 트랜잭션 · 컨텍스트

강사 답변 서비스는:

1. 강사 컨텍스트를 설정한다 — **member 트랜잭션이 아니다**. 강사 tenant 컨텍스트를 쓴다
   (`TeacherTenantDatabaseContext` 참조 · member 는 절대 만지지 않는다).
2. 자기 질문인지 확인 — `member_questions_member_teacher_select` 가 격리해 남의 질문은 0행.
3. `INSERT member_question_messages` 를 정책이 요구하는 `author_role = 'TEACHER'` 로.
4. `UPDATE member_questions` 를 `member_questions_member_teacher_update` 정책 아래 실행.

## 4. 학생 알림은 계약에 없다

🔴 강사 답변 후 학생에게 알림을 보내고 싶으면 **yaml enum 확장과 학생 알림 목록 API 신설이
먼저**다. 현재 상태:

- `member-api.yaml:2364` 의 `type` enum 에는 `QUESTION_ANSWERED` 가 이미 있다.
- 하지만 학생용 알림 목록 엔드포인트가 없다 — `GET /member/parents/me/notifications` 만 있고
  학생 경로는 계약에 없다.
- 발행 자체는 `member_notifications` INSERT 정책이 `current_checkon_account_id() IS NOT NULL`
  하나뿐이라 가능하지만, 수신자가 볼 API 가 없다.

관련 open item: **MB-44** (`QUESTION_ANSWERED` 발행 경로 부재) · **MB-45** (`LEARNING_SUBMITTED`
와 같은 근본 문제 — 학생 컨텍스트에서 학부모 계정을 해석할 수 없다).

## 5. 경로 제안

**제안 (이 PR 이 구현하지 않는다):**

```
POST /api/v1/questions/{questionId}/answers      (강사 base = /api/v1, member 아님)
  Body: { content: string }
  Response: 201 QuestionMessage
```

- 강사 인증은 기존 강사 세션 사용
- Idempotency-Key 필수 (같은 답변을 두 번 저장하지 않도록)
- 411/422 이슈: `WAITING` 이 아닌 상태에 답변하면 (예: 이미 ANSWERED 상태에서 다시 답변)
  🔴 팀 확정 필요 — 202 상태 갱신인지, 409 로 거절할지

## 6. 참고

- V41 마이그레이션 정본: `src/main/resources/db/migration/V41__member_question_notification.sql`
- 계약: `src/main/resources/openapi/member-api.yaml` (`StudentQuestion`·`QuestionMessage`)
- 상수 (열거): `com.checkon.member.question.domain.QuestionStatus`·`QuestionAuthorRole`
- 학생 서비스 (참고 구현): `com.checkon.member.question.application.StudentQuestionService`

## 7. 무접촉 (member 쪽 파일)

강사 답변 PR 은 이 문서 외에는 `com.checkon.member.**` 를 만지지 않는다. 계약 확장이 필요하면
`member-api.yaml` 은 PR0 공동 산출물이므로 별도 세션에서 승인 후 수정한다.
