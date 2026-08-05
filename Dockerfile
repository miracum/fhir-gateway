FROM docker.io/library/gradle:9.4.0-jdk25@sha256:6df8be4f28ce6fb9fc10ea49451490697751b3738100708b6fe19409d2c8b339 AS build
SHELL ["/bin/bash", "-eo", "pipefail", "-c"]
WORKDIR /home/gradle/project

COPY . .

RUN --mount=type=cache,target=/home/gradle/.gradle/caches <<EOF
gradle clean build --info
gradle jacocoTestReport
java -Djarmode=layertools -jar build/libs/fhirgateway-*.jar extract
EOF

FROM scratch AS test
WORKDIR /test
COPY --from=build /home/gradle/project/build/reports/ .
ENTRYPOINT [ "true" ]

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:2ce7f9eb870273fcb76131cf1e3b0f3e4ef48ee86679166cae1d53fe035c47aa
WORKDIR /opt/fhir-gateway
USER 65532:65532
ENV SPRING_PROFILES_ACTIVE="prod"

COPY --from=build /home/gradle/project/dependencies/ ./
COPY --from=build /home/gradle/project/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/application/ ./

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
