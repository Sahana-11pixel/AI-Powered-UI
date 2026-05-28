# ── Stage 1: Build ──────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy Maven wrapper + pom first (Docker layer cache — dependencies only rebuilt if pom changes)
COPY pom.xml .
COPY src ./src

# Install Maven inside the build image
RUN apk add --no-cache maven && mvn package -DskipTests

# ── Stage 2: Runtime (minimal image) ────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy only the fat JAR from build stage
COPY --from=builder /app/target/ui-codegen-backend-*.jar app.jar

# Non-root user for security best practice
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
