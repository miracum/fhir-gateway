FROM gradle:6.1.1-jdk11 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle build --no-daemon --info

FROM adoptopenjdk/openjdk11-openj9:alpine-jre
WORKDIR /opt/fhir-gateway
COPY --from=build /home/gradle/src/build/libs/*.jar ./fhir-gateway.jar
RUN addgroup fhirgateway && \
    adduser -D -G fhirgateway fhirgateway
USER fhirgateway
ARG VERSION=0.0.0
ENV APP_VERSION=${VERSION}
HEALTHCHECK CMD wget --quiet --tries=1 --spider http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-jar", "/opt/fhir-gateway/fhir-gateway.jar"]
