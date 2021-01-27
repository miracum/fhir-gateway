FROM gradle:6.8-jdk11 AS build
WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /gradle

COPY build.gradle settings.gradle ./
RUN gradle build || true

COPY --chown=gradle:gradle . .
RUN gradle build --info && \
    gradle jacocoTestReport && \
    awk -F"," '{ instructions += $4 + $5; covered += $5 } END { print covered, "/", instructions, " instructions covered"; print 100*covered/instructions, "% covered" }' build/jacoco/coverage.csv && \
    java -Djarmode=layertools -jar build/libs/*.jar extract

FROM gcr.io/distroless/java:11
WORKDIR /opt/fhir-gateway
COPY --from=build /home/gradle/src/dependencies/ ./
COPY --from=build /home/gradle/src/spring-boot-loader/ ./
COPY --from=build /home/gradle/src/application/ ./

USER 65532
ARG VERSION=0.0.0
ENV APP_VERSION=${VERSION} \
    SPRING_PROFILES_ACTIVE="prod"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=85", "org.springframework.boot.loader.JarLauncher"]

ARG GIT_REF=""
ARG BUILD_TIME=""
LABEL org.opencontainers.image.created=${BUILD_TIME} \
    org.opencontainers.image.authors="miracum.org" \
    org.opencontainers.image.source="https://gitlab.miracum.org/miracum/etl/fhir-gateway" \
    org.opencontainers.image.version=${VERSION} \
    org.opencontainers.image.revision=${GIT_REF} \
    org.opencontainers.image.vendor="miracum.org" \
    org.opencontainers.image.title="fhir-gateway" \
    org.opencontainers.image.description="FHIR REST facade implementing various processing and persistence operations."
