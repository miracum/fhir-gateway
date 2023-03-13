# syntax=docker/dockerfile:1.4
FROM docker.io/library/gradle:7.6.1-jdk17@sha256:22a8e848ad8d1704ec619f6bf3cda4ea793861c1a37bac261fd53e10845dfe7b AS build
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

FROM gcr.io/distroless/java17-debian11:nonroot@sha256:d5a7bb2b1bcd09d9b7ba7f7b13df39cbb2ab2ff73a0ab834a5769e59229af2f8
WORKDIR /opt/fhir-gateway
COPY --from=build /home/gradle/src/dependencies/ ./
COPY --from=build /home/gradle/src/spring-boot-loader/ ./
COPY --from=build /home/gradle/src/application/ ./

USER 65532:65532
ARG VERSION=0.0.0
ENV APP_VERSION=${VERSION} \
    SPRING_PROFILES_ACTIVE="prod"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "org.springframework.boot.loader.JarLauncher"]
