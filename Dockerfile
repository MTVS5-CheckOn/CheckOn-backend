# syntax=docker/dockerfile:1

# ==========================================
# 1. Build Stage
# Spring Boot 애플리케이션을 JAR로 빌드
# ==========================================

FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

# Gradle 관련 파일을 먼저 복사
# 소스 코드만 변경됐을 때 의존성 관련 Docker 캐시를 재사용하기 위함
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle

# Gradle Wrapper 실행 권한 부여
RUN chmod +x gradlew

# 애플리케이션 소스 복사
COPY src src

# Spring Boot 실행용 JAR 생성
# 테스트는 Docker 이미지 빌드 단계에서는 제외
RUN ./gradlew --no-daemon clean bootJar -x test


# ==========================================
# 2. Runtime Stage
# 만들어진 JAR만 가져와 실제 애플리케이션 실행
# ==========================================

FROM eclipse-temurin:25-jre

WORKDIR /app

# 컨테이너를 root가 아닌 일반 사용자로 실행하기 위한 계정 생성
RUN useradd -r -u 1001 appuser

# Build Stage에서 생성된 Spring Boot JAR를 복사
COPY --from=builder /workspace/build/libs/*.jar app.jar

# 이후 프로세스는 appuser 권한으로 실행
USER appuser

# Spring Boot 기본 포트
EXPOSE 8080

# 컨테이너가 시작될 때 Spring Boot 애플리케이션 실행
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "/app/app.jar"]
