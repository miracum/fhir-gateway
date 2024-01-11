# syntax=docker/dockerfile:1.4@sha256:9ba7531bd80fb0a858632727cf7a112fbfd19b17e94c4e84ced81e24ef1a0dbc
FROM docker.io/library/gradle:8.4.0-jdk21@sha256:97f1ca124aa6853e9b17d543d7ef75c8aecf64719606ade5862344a630fb927b AS build
WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /gradle

COPY build.gradle settings.gradle gradle.properties ./

RUN gradle --no-daemon build || true

COPY --chown=gradle:gradle . .

RUN <<EOF
gradle --no-daemon build  --info
gradle --no-daemon jacocoTestReport
awk -F"," '{ instructions += $4 + $5; covered += $5 } END { print covered, "/", instructions, " instructions covered"; print 100*covered/instructions, "% covered" }' build/reports/jacoco/test/jacocoTestReport.csv
java -Djarmode=layertools -jar build/libs/*.jar extract
EOF

FROM gcr.io/distroless/java21-debian12:nonroot@sha256:5b3594784a4479c9bcdde3ccd5dc68a63265f91047526f05561211f98de7b575
WORKDIR /opt/fhir-gateway
COPY --from=build /home/gradle/src/dependencies/ ./
COPY --from=build /home/gradle/src/spring-boot-loader/ ./
COPY --from=build /home/gradle/src/application/ ./

USER 65532:65532
ENV SPRING_PROFILES_ACTIVE="prod"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "org.springframework.boot.loader.JarLauncher"]
