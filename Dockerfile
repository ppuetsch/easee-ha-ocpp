# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
ARG GIT_SHA=
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
 && ./gradlew bootJar -x test -PgitSha="${GIT_SHA}" --no-daemon --console=plain \
 && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:25-jre AS runtime
ARG APP_UID=10001
ARG APP_GID=10001
# Die Anwendung nutzt sonst relative Pfade (secrets/, data/) unter /app; die Volumes liegen unter /secrets und /data.
ENV SERVER_PORT=8080 \
    EASEE_OCPP_SSL_KEYSTOREPATH=/secrets/keystore.p12 \
    EASEE_CONTROL_HISTORYFILE=/data/control-history.jsonl

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid "${APP_GID}" easee \
 && useradd --system --uid "${APP_UID}" --gid easee --home-dir /app --shell /usr/sbin/nologin easee \
 && mkdir -p /app /data /secrets \
 && chown easee:easee /data

COPY --from=build --chown=easee:easee /workspace/app.jar /app/app.jar

WORKDIR /app
USER easee
VOLUME ["/data", "/secrets"]
EXPOSE 8080 8443

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl --fail --silent --show-error "http://localhost:${SERVER_PORT}/actuator/health" > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
