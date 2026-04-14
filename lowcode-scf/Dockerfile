# Multi-stage build for the Spring Cloud Function / Spring Cloud Stream
# document-enricher.
#
# NOTE: The build context for this image is the repository root (not the
# lowcode-scf/ directory) because the service depends on the sibling
# commons-observability Gradle project via a composite build. docker-compose
# invokes this Dockerfile with `context: .` and `dockerfile: lowcode-scf/Dockerfile`.

FROM gradle:8.10-jdk17 AS build
WORKDIR /workspace

# Sibling shared library — referenced by lowcode-scf's settings.gradle via
# includeBuild('../commons-observability').
COPY commons-observability /workspace/commons-observability

# Service sources.
COPY lowcode-scf/build.gradle lowcode-scf/settings.gradle /workspace/lowcode-scf/
COPY lowcode-scf/src /workspace/lowcode-scf/src
# Ship the AsyncAPI spec inside the JAR so the ServiceCardController can
# read it at runtime without needing a mounted config volume.
COPY lowcode-scf/asyncapi.yaml /workspace/lowcode-scf/src/main/resources/asyncapi.yaml

WORKDIR /workspace/lowcode-scf
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/lowcode-scf/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
