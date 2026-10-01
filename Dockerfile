FROM docker.io/library/gradle:9.6.0-jdk25@sha256:e3905233ae349e72016daf8a0e19f085a1dd89ded8ec88b3d8335d3fd0b350f4 AS build
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

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:ca60da1345c0f17b6d019049e6749e15f10fd3c0da86dec938d2b4ec565d0629
WORKDIR /opt/fhir-gateway
USER 65532:65532
ENV SPRING_PROFILES_ACTIVE="prod"

COPY --from=build /home/gradle/project/extracted/dependencies/ ./
COPY --from=build /home/gradle/project/extracted/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/extracted/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/extracted/application/ ./

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
