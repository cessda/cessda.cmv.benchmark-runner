# ── Stage 1: Build ───────────────────────────────────────────────────────────
# Uses the official Maven + JDK 21 image to compile and package the fat JAR.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copy the POM first so Maven dependency layer is cached independently of source changes.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build the fat JAR (skipping tests for the Docker build).
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
# Minimal JRE-only image keeps the final image small.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy only the fat JAR from the build stage.
COPY --from=build /build/target/benchmark-*.jar app.jar

# ── Volume mount points ───────────────────────────────────────────────────────
# benchmark-data    : guids_*.txt files written by fetch-identifiers and read by run-assessment
# benchmark-results : JSON result files written by run-assessment and read by generate-manifest
#                     Also served as static files so the dashboard can fetch summary.json etc.
VOLUME ["/data", "/results"]

# Spring Boot listens on 8080.
EXPOSE 8080

# ── Healthcheck ───────────────────────────────────────────────────────────────
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
