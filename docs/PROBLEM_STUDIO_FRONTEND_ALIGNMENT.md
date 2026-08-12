# 문제 출제 스튜디오 프론트·AI 계약 정렬

- 기준 화면: 2026-08-12 제공된 Step 1~4 SVG
- 기준 백엔드: `codex/feature/problem-generation-kafka-recovery/37`
- 우선순위: 프론트 화면에 필요한 백엔드 데이터 구조를 우선하고, AI 계약 차이는 Kafka 변환 경계에 격리한다.

## 1. 화면별 백엔드 책임

| 단계 | 화면 요구 | 백엔드 데이터·기능 |
| --- | --- | --- |
| 1 대상·약점 확인 | 학생 목록, 클래스·과목, 관리 일수, 특이사항, 최근 8주 영역×유형 분석, 복수 영역별 문항 수 | ACTIVE 학생 페이지, 최근 30일 Alert 집계, Learning Record 8주 집계, 영역×유형별 요청 조건 |
| 2 출제 조건 | 객관식 문항 수, 공통 난이도, 생성 요청 | 영역별 수의 합계 검증, 요청 snapshot·Outbox 원자 저장, Kafka 발행 |
| 3 초안 검토 | 검증 통과·검토 필요·검증 불가·폐기/제외 집계, 문두·선지·정답·근거·메시지 | AI 원문 보존과 별도 문항 read model, 검증 상태별 집계 |
| 4 저장 및 발행 | 문항 펼침·선택, PDF, 문항 저장, 대상 학생 발행 | 선택 상태, 멱등 저장 세트, 멱등 발행 과제, 인쇄용 구조화 응답 |

## 2. 프론트 우선으로 바뀌는 요청 구조

기존 AI 문서는 한 요청에 `manual_targets`, 단일 `area_tag`, `type_tags`, 전체 `count`를 사용한다.
화면은 영역×유형 조합마다 문항 수가 다르므로 다음 배열이 정본이다.

```json
{
  "studentId": "019fd78d-deb7-772c-89e1-318d374a6ddc",
  "targets": [
    {"areaTag": "reading", "typeTag": "FACT", "count": 4},
    {"areaTag": "literature", "typeTag": "INFER", "count": 3}
  ],
  "difficulty": "LOW"
}
```

Kafka request에는 같은 의미를 `generation_targets`로 전달한다. 기존 `area_tag`, `type_tags`, `count`는
AI 팀의 전환 기간을 위한 요약값이다. 여러 영역이면 `area_tag`는 `mixed`이며, `count`는 배열 합계다.
내부 학생 UUID와 실명은 포함하지 않고 기존 `st_` alias만 사용한다.

## 3. AI 결과에 필요한 최소 문항 계약

Step 3·4를 그리려면 성공 결과의 각 문항에 최소한 다음 의미가 필요하다.

```json
{
  "id": "problem-0001",
  "stem": "문두",
  "options": ["선지 1", "선지 2", "선지 3", "선지 4", "선지 5"],
  "correct_answer": "선지 1",
  "explanation": "정답 해설",
  "source_basis": "출제 근거",
  "validation_status": "passed",
  "validation_message": null
}
```

백엔드 projector는 AI 전환을 위해 `stem/question/prompt`, `options/choices`,
`correct_answer/answer`, `source_basis/generation_basis`, `validation_status/verification.status` 후보를
한 곳에서 해석한다. 문두 또는 선지가 없으면 원문 결과만 보존하고 문항 read model을 추론 생성하지 않는다.

## 4. 충돌과 처리 결과

| 항목 | 기존 AI 구현 | 프론트 요구 | 적용 |
| --- | --- | --- | --- |
| 대상 선택 | 학생 또는 클래스, taxonomy 목표 직접 입력 | 학생 진단 목록에서 한 학생 선택 | 기존 클래스 요청은 보존하고 스튜디오는 학생 전용 |
| 목표 | `manual_targets` 1~20 | 영역×유형 복수 선택과 조합별 수 | `generation_targets` 추가, AI 팀 계약 갱신 필요 |
| 문항 수 | 백엔드 1~10, AI 문서 최대 20 | 화면 예시에 12 | 레거시 API는 10 유지, 스튜디오는 합계 최대 20 |
| 난이도 | 선택 가능 | Step 2 필수 | 스튜디오에서 필수 |
| 결과 | 원문 JSON, ID만 있어도 성공 | 전체 문두·선지·정답·근거·검증 | 원문과 투영 모델 병행, 불완전 결과는 `UNSUPPORTED` |
| 검증 | AI 결과 상태 | 4개 표시 상태 | AI 상태를 투영하되 교사 저장·발행과 분리 |
| 저장·발행 | 미구현 | 문항 저장, 특정 학생에게 발행 | 백엔드 소유 세트·과제 테이블 추가 |
| PDF | AI 범위 아님 | 다운로드 버튼 | 백엔드 인쇄 데이터, 프론트 PDF 렌더링 |

## 5. 의도적으로 확정하지 않은 항목

- `WEAK_CONFIRMED`를 자동 판단할 점수 차이 임계값
- 문항 수정·교체·재생성 메뉴의 세부 동작
- AI `generation_targets` 최종 schema version과 상호 fixture
- 학생용 과제 목록·풀이·제출·채점 API
- PDF 용지, 정답지 포함 여부, 워터마크와 한글 폰트

이 항목은 현재 화면만으로 제품 규칙을 확정할 수 없다. 백엔드는 데이터 손실 없이 후속 계약을 추가할 수
있는 구조만 마련한다.
