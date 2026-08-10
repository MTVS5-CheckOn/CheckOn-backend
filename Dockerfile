# =========================
# 1. 빌드 이미지 생성 단계
# =========================

# Gradle 9.5.1 + JDK 25가 설치된 이미지를 빌드 환경으로 사용
# 이 단계는 실제 서버 실행용이 아니라 Spring Boot 프로젝트를 빌드하여 JAR를 만드는 용도
FROM gradle:9.5.1-jdk25 AS build

# 이후 명령어가 실행될 작업 디렉터리 지정
WORKDIR /home/app

# 현재 프로젝트의 모든 파일을 컨테이너의 /home/app으로 복사
# 복사된 파일의 소유자를 gradle 사용자/그룹으로 지정
COPY --chown=gradle:gradle . /home/app

# Gradle Wrapper에 실행 권한을 부여한 뒤 프로젝트 빌드
# -x test : 테스트는 실행하지 않고 빌드
# 빌드 성공 시 /home/app/build/libs/ 아래에 JAR 파일이 생성됨
RUN chmod +x ./gradlew && ./gradlew build -x test


# =========================
# 2. 실제 실행 이미지 생성 단계
# =========================

# 빌드는 이미 끝났으므로 JDK/Gradle이 필요 없음
# Java 애플리케이션 실행에 필요한 JRE 25만 포함된 이미지를 사용
FROM eclipse-temurin:25-jre

# /tmp 디렉터리를 볼륨으로 지정
# Spring Boot/Tomcat 등이 임시 파일을 사용할 수 있는 공간
VOLUME /tmp

# 이 컨테이너가 8080 포트를 사용하는 애플리케이션임을 명시
# 주의: 실제 호스트 포트를 연결하는 것은 아니며,
# docker run -p 8080:8080 ... 등의 설정이 별도로 필요함
EXPOSE 8080

# build 단계에서 만들어진 JAR 파일만 현재 실행 이미지로 복사
# 최종 이미지 안에서는 /app.jar라는 이름으로 사용
COPY --from=build /home/app/build/libs/*.jar /app.jar

# 컨테이너가 시작될 때 실행할 명령
#
# 실제 실행되는 명령:
# java -Dspring.profiles.default=prod -jar /app.jar
#
# spring.profiles.default=prod:
# 별도로 활성화된 Spring Profile이 없으면 prod 프로파일을 기본값으로 사용
#
# -jar /app.jar:
# 앞에서 복사한 Spring Boot JAR 애플리케이션 실행
ENTRYPOINT ["sh", "-c", "java -Dspring.profiles.default=prod -jar /app.jar"]