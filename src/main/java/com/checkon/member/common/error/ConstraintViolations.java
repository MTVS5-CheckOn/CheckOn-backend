package com.checkon.member.common.error;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * DB 제약 위반에서 <b>제약 이름</b>을 꺼낸다.
 *
 * <p>🔴 <b>메시지 문자열을 파싱하지 않는다</b>(코드 규칙 §5). PostgreSQL 의 오류 문구는 로케일과
 * 서버 버전에 따라 바뀌므로, substring 매칭은 DB 를 올리는 순간 조용히 깨진다. 제약 이름은
 * 프로토콜 필드({@code ErrorResponse} 의 {@code C} 항목)라 문구와 무관하게 안정적이다.</p>
 *
 * <p>🔴 <b>왜 리플렉션인가</b> — 제약 이름을 타입 안전하게 읽으려면
 * {@code org.postgresql.util.PSQLException} 을 import 해야 하는데, 이 저장소에서 드라이버는
 * {@code runtimeOnly} 라 {@code src/main} 컴파일 클래스패스에 없다(실측 {@code build.gradle:36}).
 * {@code build.gradle} 은 무접촉이라 스코프를 바꿀 수 없다. 그래서 런타임에만 존재하는
 * 접근자를 리플렉션으로 부르고, <b>못 읽으면 비워서 돌려준다</b> — 호출부가 그것을
 * "알 수 없는 제약"으로 취급해 원래 예외를 그대로 올리도록.</p>
 */
public final class ConstraintViolations {

	/** 고유 제약 위반. SQL 표준 SQLSTATE 라 드라이버에 의존하지 않는다. */
	public static final String UNIQUE_VIOLATION = "23505";

	private static final String SERVER_ERROR_MESSAGE = "getServerErrorMessage";
	private static final String CONSTRAINT = "getConstraint";

	private ConstraintViolations() {
	}

	/**
	 * @return 제약 이름. 위반이 {@code 23505} 가 아니거나 이름을 읽지 못하면 {@code null} —
	 *         🔴 "모른다"는 뜻이며, 호출부는 이 경우 예외를 삼키지 말고 그대로 올린다
	 */
	public static String constraintNameOf(DataIntegrityViolationException exception) {
		Throwable cause = exception.getMostSpecificCause();
		if (!(cause instanceof SQLException sqlException)) {
			return null;
		}
		if (!UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
			return null;
		}
		return readConstraint(sqlException);
	}

	private static String readConstraint(SQLException sqlException) {
		try {
			Method serverErrorMessage =
				sqlException.getClass().getMethod(SERVER_ERROR_MESSAGE);
			Object message = serverErrorMessage.invoke(sqlException);
			if (message == null) {
				return null;
			}
			Object constraint = message.getClass().getMethod(CONSTRAINT).invoke(message);
			return constraint instanceof String name && !name.isBlank() ? name : null;
		}
		catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException
			   | RuntimeException reflectionFailure) {
			// 🔴 삼키는 게 아니다. 이름을 "모른다"로 돌려주면 호출부가 원래 예외를 올려
			//    500 이 된다 — 틀린 409 로 위장하는 것보다 낫다.
			return null;
		}
	}
}
