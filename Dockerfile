FROM docker.io/library/gradle:9.7.0-jdk25@sha256:35b2b4054977b95348bdd126e808e245fc1e28e56537b51cfd90c7f355b5a105 AS build
SHELL ["/bin/bash", "-eo", "pipefail", "-c"]
WORKDIR /home/gradle/project

COPY . .

RUN --mount=type=cache,target=/home/gradle/.gradle/caches <<EOF
gradle clean build --info
gradle jacocoTestReport
java -Djarmode=tools -jar build/libs/fhirgateway-*.jar extract --layers --launcher --destination extracted
EOF

FROM scratch AS test
WORKDIR /test
COPY --from=build /home/gradle/project/build/reports/ .
ENTRYPOINT [ "true" ]

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:2ce7f9eb870273fcb76131cf1e3b0f3e4ef48ee86679166cae1d53fe035c47aa
WORKDIR /opt/fhir-gateway
USER 65532:65532
ENV SPRING_PROFILES_ACTIVE="prod"

COPY --from=build /home/gradle/project/extracted/dependencies/ ./
COPY --from=build /home/gradle/project/extracted/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/extracted/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/extracted/application/ ./

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
