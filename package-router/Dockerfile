# Multi-stage build for the package-router service.
#
# Build context is the REPOSITORY ROOT — the service depends on the
# sibling commons-observability Gradle project via a composite build,
# so both need to be inside the build context. docker-compose's
# image-builder invokes:
#     docker build -t package-router:latest -f package-router/Dockerfile .

FROM gradle:8.10-jdk17 AS build
WORKDIR /workspace

# Sibling shared library.
COPY commons-observability /workspace/commons-observability

# Service sources.
COPY package-router/build.gradle package-router/settings.gradle /workspace/package-router/
COPY package-router/src /workspace/package-router/src

WORKDIR /workspace/package-router
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/package-router/build/libs/app.jar app.jar
EXPOSE 8080
# Kogito adds startup overhead; give the JVM a bit more headroom up-front.
ENTRYPOINT ["java", "-Xmx384m", "-Xms192m", "-jar", "app.jar"]
