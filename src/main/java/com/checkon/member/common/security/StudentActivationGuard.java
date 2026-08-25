package com.checkon.member.common.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.checkon.member.auth.domain.MemberActivationStatus;
import com.checkon.member.common.error.MemberErrorCode;
import com.checkon.member.common.error.MemberErrorResponse;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 대기 학생이 학습 기능에 닿지 못하게 막는다.
 *
 * <p>판정은 Controller 가 아니라 여기 한 곳에서 한다(설계 §4-4). 컨트롤러마다 분기하면
 * 새 엔드포인트가 생길 때 빠뜨린다.</p>
 *
 * <p>🔴 fail-closed 다. 활성화 행이 없으면({@code null}) 통과가 아니라 403 이다 —
 * 가입 트랜잭션이 깨진 계정에 학습 기능을 열어주지 않는다.</p>
 *
 * <p>🔴 예외를 던지지 않고 <b>응답을 직접 쓴다.</b> 인터셉터에서 던진 예외는
 * {@code @RestControllerAdvice} 가 항상 잡아주지 않는다 — 대상 경로에 컨트롤러가 없으면
 * 정적 리소스 핸들러가 매칭돼 {@code HandlerMethod} 가 아니게 되고, 그러면 member 봉투가 아니라
 * 500 ServletException 이 나간다(실측). 차단은 <b>컨트롤러의 존재와 무관하게</b> 같은 모양이어야 한다.</p>
 */
@Component
public class StudentActivationGuard implements HandlerInterceptor {

	private static final Logger log = LoggerFactory.getLogger(StudentActivationGuard.class);

	/**
	 * 대기({@code PENDING_PARENT_LINK}) 학생에게 열어 두는 경로.
	 *
	 * <p>MB-02 CONFIRMED 2026-08-25 — 허용 3개(세션·활성화상태·로그아웃). 초대는 활성화 후.</p>
	 *
	 * <p>🔴 {@code POST /api/v1/auth/logout} 은 3번째 허용 API 지만 {@code /api/v1/member/**}
	 * matcher 밖이라 이 guard 가 아예 보지 않는다. 그래서 이 배열에는 <b>2개</b>만 있다.</p>
	 *
	 * <p>🔴 강사 초대 검증·등록을 여기 넣지 마라. 넣는 순간 대기 학생이 학부모 연결 없이
	 * 강사에게 먼저 붙는 경로가 열리고, 그건 MB-02 확정을 어기는 것이다.</p>
	 */
	static final List<AllowedPath> PENDING_STUDENT_ALLOWED = List.of(
		new AllowedPath(HttpMethod.GET, "/api/v1/member/auth/session"),
		new AllowedPath(HttpMethod.GET, "/api/v1/member/auth/students/activation-status")
	);

	private final MemberSubjectResolver subjectResolver;
	private final ObjectMapper objectMapper;

	public StudentActivationGuard(
		MemberSubjectResolver subjectResolver,
		ObjectMapper objectMapper
	) {
		this.subjectResolver = subjectResolver;
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean preHandle(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler
	) throws IOException {
		Optional<MemberSubject> subject = subjectResolver.currentSubject();
		if (subject.isEmpty() || subject.get().role() != MemberRole.STUDENT) {
			return true;
		}
		MemberActivationStatus status = subject.get().activationStatus();
		if (status == MemberActivationStatus.ACTIVE) {
			return true;
		}
		if (status == MemberActivationStatus.PENDING_PARENT_LINK && isAllowed(request)) {
			return true;
		}
		// 🔴 로그에는 accountId 만. 상태·경로를 함께 남기면 열거 단서가 된다.
		log.info("student activation guard blocked account {}", subject.get().accountId());
		writeForbidden(response);
		return false;
	}

	private void writeForbidden(HttpServletResponse response) throws IOException {
		MemberErrorCode code = MemberErrorCode.STUDENT_ACTIVATION_REQUIRED;
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(
			response.getOutputStream(),
			MemberErrorResponse.of(code, "student is not activated yet", null));
	}

	private boolean isAllowed(HttpServletRequest request) {
		return PENDING_STUDENT_ALLOWED.stream().anyMatch(allowed -> allowed.matches(request));
	}

	/** 메서드까지 함께 본다. 같은 경로의 다른 메서드가 딸려 열리지 않게 한다. */
	record AllowedPath(HttpMethod method, String path) {

		boolean matches(HttpServletRequest request) {
			return method.name().equalsIgnoreCase(request.getMethod())
				&& path.equals(request.getRequestURI());
		}
	}
}
