# syntax=docker/dockerfile:1

# Build stage: compile and package the jar. Tests run in CI, not in the image build.
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar \
    && java -Djarmode=tools -jar build/libs/payment-gateway-challenge-java-*.jar \
       extract --layers --launcher --destination extracted

# Runtime stage: JRE only, non-root. Layers go from least to most often changed, so a code-only
# change rebuilds just the last layer.
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S gateway && adduser -S gateway -G gateway
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER gateway
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8090/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
