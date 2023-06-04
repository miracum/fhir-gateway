# syntax=docker/dockerfile:1.4
FROM docker.io/library/gradle:7.6.1-jdk17@sha256:4b2997d35c891c405c053c653965d45348ba36e8d10d40dcbba7e05fc0bff389 AS build
WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /gradle

COPY build.gradle settings.gradle gradle.properties ./

RUN gradle --no-daemon build || true

COPY --chown=gradle:gradle . .

RUN <<EOF
gradle build --info
gradle jacocoTestReport
awk -F"," '{ instructions += $4 + $5; covered += $5 } END { print covered, "/", instructions, " instructions covered"; print 100*covered/instructions, "% covered" }' build/jacoco/coverage.csv
java -Djarmode=layertools -jar build/libs/*.jar extract
EOF

FROM gcr.io/distroless/java17-debian11:nonroot@sha256:61463fa9d1bb9994de4e50e71f3e487d0e61f1676e26306388a743ff96311777
WORKDIR /opt/fhir-gateway
COPY --from=build /home/gradle/src/dependencies/ ./
COPY --from=build /home/gradle/src/spring-boot-loader/ ./
COPY --from=build /home/gradle/src/application/ ./

USER 65532:65532
ARG VERSION=0.0.0
ENV APP_VERSION=${VERSION} \
    SPRING_PROFILES_ACTIVE="prod"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "org.springframework.boot.loader.JarLauncher"]
