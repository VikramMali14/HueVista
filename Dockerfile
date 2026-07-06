# syntax=docker/dockerfile:1
# ---- Build stage: compile + package with the Maven wrapper (correct Maven version) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# Cache dependencies first for faster rebuilds.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

# ---- Runtime stage: slim JRE ----
FROM eclipse-temurin:25-jre
WORKDIR /app
# curl is only for the container HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# Run as a non-root user.
RUN useradd -r -u 1001 huevista
COPY --from=build /app/target/*.jar app.jar
# The logback FILE appender writes to ./logs/huevista.log (relative to WORKDIR
# /app). /app is root-owned, so the non-root user can't create the dir and file
# logging fails on every boot. Pre-create it owned by the runtime user.
RUN mkdir -p /app/logs && chown -R huevista:huevista /app/logs
USER huevista
EXPOSE 8080
ENV JAVA_OPTS=""
# Pin the JVM to IST. Timestamps are stored zone-naive (LocalDateTime), so a
# UTC host would silently shift every expiry (share links, subscriptions,
# access codes) by 5.5 hours. The product is India-only; override TZ only if
# you also migrate the schema to timestamptz/Instant.
ENV TZ=Asia/Kolkata
# Probe the actuator health endpoint (public, status-only). start-period covers
# JVM boot + Flyway migrations on first run.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
