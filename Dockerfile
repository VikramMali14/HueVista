# syntax=docker/dockerfile:1
# ---- Build stage: compile + package with the Maven wrapper (correct Maven version) ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
# Cache dependencies first for faster rebuilds.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

# ---- Runtime stage: slim JRE ----
FROM eclipse-temurin:17-jre
WORKDIR /app
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
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
