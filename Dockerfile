# ----------------------------------------------------------------------------
# Stage 1: Build Stage
# ----------------------------------------------------------------------------
# Use official Maven image with Eclipse Temurin JDK 21 on Alpine Linux.
# This stage is only responsible for compiling and packaging the application.
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

# Set working directory inside container.
# All subsequent commands will run from /build.
WORKDIR /build

# Copy Maven project descriptor first.
# This helps Docker layer caching because dependencies are downloaded
# only when pom.xml changes.
COPY pom.xml .

# Copy application source code.
COPY src ./src

# -----------------------------------------------------------------------------
# Download all Maven dependencies in advance.
#
# --mount=type=cache,target=/root/.m2
# Creates a persistent cache for Maven dependencies between Docker builds.
# Without this, Maven downloads dependencies every build which is slow.
#
# dependency:go-offline
# Downloads all required dependencies/plugins before actual build.
# Useful for faster and more reliable builds.
# -----------------------------------------------------------------------------
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q dependency:go-offline

# -----------------------------------------------------------------------------
# Build Spring Boot application.
#
# package
# Compiles code + runs packaging phase and creates executable jar.
#
# -DskipTests
# Skips test execution to speed up Docker image build.
# Usually used in CI/CD container builds.
# -----------------------------------------------------------------------------
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q package -DskipTests


# -----------------------------------------------------------------------------
# Extract Spring Boot layered jar.
#
# Spring Boot creates layered jars containing:
# - dependencies
# - spring boot loader
# - snapshot dependencies
# - application classes
#
# Why extract layers?
# Docker caches layers separately.
# If only application code changes, dependency layers are reused,
# making rebuilds much faster.
#
# java -Djarmode=layertools
# Enables Spring Boot layertools mode.
#
# extract --destination /build/extracted
# Extracts jar into folders for optimized Docker layering.
# -----------------------------------------------------------------------------
RUN ls -la target && \
    JAR_FILE=$(ls target/*.jar | grep -v '\.original$') && \
    echo "Using jar: ${JAR_FILE}" && \
    java -Djarmode=layertools -jar ${JAR_FILE} extract --destination /build/extracted && \
    ls -R /build/extracted


# ----------------------------------------------------------------------------
# Stage 2: Runtime Stage
# ----------------------------------------------------------------------------
# Use lightweight JRE-only image for running application.
# Smaller than JDK image -> reduces final image size.
FROM eclipse-temurin:21-jre-alpine AS runtime

# -----------------------------------------------------------------------------
# Create non-root user and group.
#
# Running containers as root is a security risk.
# appuser will run the Spring Boot application safely.
# -----------------------------------------------------------------------------
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

# Set runtime working directory.
WORKDIR /app

# -----------------------------------------------------------------------------
# Copy extracted Spring Boot layers from builder stage.
#
# COPY --from=builder
# Copies files from previous build stage.
#
# --chown=appuser:appgroup
# Ensures copied files belong to non-root user.
#
# Separate COPY instructions improve Docker caching:
# - dependencies rarely change
# - application layer changes frequently
# -----------------------------------------------------------------------------

# Third-party libraries
COPY --from=builder --chown=appuser:appgroup /build/extracted/dependencies/          ./

# Spring Boot launcher classes
COPY --from=builder --chown=appuser:appgroup /build/extracted/spring-boot-loader/    ./

# Snapshot dependencies (changing libraries)
COPY --from=builder --chown=appuser:appgroup /build/extracted/snapshot-dependencies/ ./

# Application compiled classes/resources
COPY --from=builder --chown=appuser:appgroup /build/extracted/application/           ./

# -----------------------------------------------------------------------------
# Set default Spring profile.
#
# Can be overridden at runtime:
# docker run -e SPRING_PROFILES_ACTIVE=prod ...
# -----------------------------------------------------------------------------
ENV SPRING_PROFILES_ACTIVE=local

# Switch to non-root user.
USER appuser

# Inform Docker that application listens on port 8080.
# Documentation purpose only; does not actually publish port.
EXPOSE 8080

# -----------------------------------------------------------------------------
# Container startup command.
#
# java
# Starts JVM.
#
# -XX:+UseContainerSupport
# Makes JVM aware of Docker container memory/CPU limits.
#
# -XX:MaxRAMPercentage=75.0
# JVM heap can use up to 75% of container memory.
#
# -Djava.security.egd=file:/dev/./urandom
# Faster secure random generation during startup.
#
# org.springframework.boot.loader.launch.JarLauncher
# Spring Boot launcher that starts application from extracted layers.
# -----------------------------------------------------------------------------
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]