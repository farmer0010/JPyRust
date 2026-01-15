# Stage 1: Build
FROM gradle:jdk17 AS builder
WORKDIR /app

# Copy subprojects
COPY java-api/ java-api/
COPY demo-web/ demo-web/

# Build the application
# Use -x test to speed up and avoid environment-specific test failures in build container
WORKDIR /app/demo-web
RUN gradle clean build -x test --no-daemon

# Stage 2: Run
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built jar from builder stage
COPY --from=builder /app/demo-web/build/libs/*.jar app.jar

# Expose port
EXPOSE 8080

# Run the application
# Note: This image does NOT contain Python. 
# It verifies that JPyRust can carry its own Python runtime (Desert Mode).
ENTRYPOINT ["java", "-jar", "app.jar"]
