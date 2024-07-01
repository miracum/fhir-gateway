FROM docker.io/library/gradle:8.8.0-jdk21@sha256:ea95b17d3d287698b286aad1de10ca43692c16e7cc5c728043f2cd2da5f87e50 AS build
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

FROM docker.io/library/debian:12.5-slim@sha256:67f3931ad8cb1967beec602d8c0506af1e37e8d73c2a0b38b181ec5d8560d395 AS jemalloc
# hadolint ignore=DL3008
RUN <<EOF
apt-get update
apt-get install -y --no-install-recommends libjemalloc-dev
apt-get clean
rm -rf /var/lib/apt/lists/*
EOF

FROM gcr.io/distroless/java21-debian12:nonroot@sha256:58f89bf86a6d0f71904a14382bab3116f97b77cd07ee44416a6628cfd971f944
WORKDIR /opt/fhir-gateway

COPY --from=jemalloc /usr/lib/x86_64-linux-gnu/libjemalloc.so /usr/lib/x86_64-linux-gnu/libjemalloc.so

COPY --from=build /home/gradle/project/dependencies/ ./
COPY --from=build /home/gradle/project/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/application/ ./

USER 65532:65532
ENV SPRING_PROFILES_ACTIVE="prod"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
