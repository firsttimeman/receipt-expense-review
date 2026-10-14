# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src/main ./src/main
# 테스트는 별도로 실행합니다. 이미지에는 실행용 JAR만 복사합니다.
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 receipt \
    && useradd --uid 10001 --gid receipt --no-create-home receipt

WORKDIR /app
COPY --from=build --chown=receipt:receipt /workspace/build/libs/*.jar ./app.jar
USER receipt
EXPOSE 8080

# 공개된 첫 화면이 응답하는지 확인합니다. S3/OpenAI의 정상 동작까지 보장하지는 않습니다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --output /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
