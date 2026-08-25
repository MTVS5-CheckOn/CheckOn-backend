package com.checkon.member.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * member 통합 테스트가 <b>하나의</b> PostgreSQL 컨테이너를 공유한다.
 *
 * <p>🔴 왜 공유하나 — 클래스마다 컨테이너를 띄웠더니 Docker 가 고갈돼 승우님 테스트 4개가
 * {@code Could not connect to Ryuk} · {@code Can't get Docker image} 로 죽었다(실측).
 * <b>내 테스트가 남의 테스트를 깨뜨린 것</b>이라 개수를 줄이는 게 맞다.</p>
 *
 * <p>🔴 왜 {@code @Testcontainers} · {@code @Container} 를 쓰지 않나 —
 * 그 확장은 <b>테스트 클래스가 끝나면 컨테이너를 멈춘다.</b> 정적 필드를 여러 클래스가 공유하면
 * <b>먼저 끝난 클래스가 뒤 클래스의 컨테이너를 죽인다</b> — 실측으로
 * {@code Connection is not available ... total=0} 이 19건 났고, 원인을 커넥션 고갈로 오진했다.
 * ({@code max_connections} 는 300 이었고 실제 사용은 12 였다.)
 * 그래서 수명을 JVM 에 맡긴다 — 정적 초기화로 한 번 시작하고, 정리는 Ryuk 이 종료 시 한다.</p>
 *
 * <p>🔴 상태를 공유하므로 <b>하위 클래스는 각자 {@code @BeforeEach} 에서 자기 픽스처를 지운다.</b>
 * 지우지 않으면 실행 순서에 따라 결과가 달라진다.</p>
 *
 * <p>🔴 하위 클래스는 {@code spring.datasource.hikari.maximum-pool-size} 를 작게 둔다.
 * Spring 컨텍스트마다 풀이 하나씩 붙고, 캐시된 옛 컨텍스트도 커넥션을 계속 붙잡는다.</p>
 */
public abstract class MemberPostgresSupport {

	@ServiceConnection
	public static final PostgreSQLContainer POSTGRES =
		new PostgreSQLContainer("postgres:18.4");

	static {
		POSTGRES.start();
	}
}
