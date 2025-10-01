FROM docker.io/library/gradle:9.1.0-jdk21@sha256:fc20fc72639e969e5c45006b66288be8d1fc18968927e859a8107a8c303ef7b2 AS build
WORKDIR /home/gradle/project

COPY --chown=gradle:gradle . .

RUN --mount=type=cache,target=/home/gradle/.gradle/caches <<EOF
gradle clean build --info --no-daemon
gradle jacocoTestReport --no-daemon
java -Djarmode=layertools -jar build/libs/fhirgateway-*.jar extract
EOF

FROM scratch AS test
WORKDIR /test
COPY --from=build /home/gradle/project/build/reports/ .
ENTRYPOINT [ "true" ]

FROM gcr.io/distroless/java21-debian12:nonroot@sha256:dfea876744f635b5fea3ea53099410cfc5d17aeea7b62887310512d78094f6f3
WORKDIR /opt/fhir-gateway

COPY --from=build /home/gradle/project/dependencies/ ./
COPY --from=build /home/gradle/project/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/application/ ./

USER 65532:65532
ENV SPRING_PROFILES_ACTIVE="prod"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
