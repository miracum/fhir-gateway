FROM docker.io/library/gradle:9.2.1-jdk25@sha256:2212a01f25c1e5554be919dabfe1122821d814ac2cdf0c21b4a2e9bfdb08f3e9 AS build
SHELL ["/bin/bash", "-eo", "pipefail", "-c"]
WORKDIR /home/gradle/project

COPY --chown=gradle:gradle . .

RUN --mount=type=cache,target=/home/gradle/.gradle/caches <<EOF
gradle clean build --info
gradle jacocoTestReport
java -Djarmode=layertools -jar build/libs/fhirgateway-*.jar extract
EOF

FROM scratch AS test
WORKDIR /test
COPY --from=build /home/gradle/project/build/reports/ .
ENTRYPOINT [ "true" ]

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:29a8dfd3f2357a0b32839c2728893f5bcdacdde00eafa45c5c7b95e6f264b2b1
WORKDIR /opt/fhir-gateway
USER 65532:65532
ENV SPRING_PROFILES_ACTIVE="prod"

COPY --from=build /home/gradle/project/dependencies/ ./
COPY --from=build /home/gradle/project/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/application/ ./

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
