# Workload generator: the Gradle application on a Java 21 JRE, run by pouw-deploy as workload.
# curl is there for the compose healthcheck.
FROM eclipse-temurin:24-jdk AS build
WORKDIR /src
COPY . .
# The cache mount keeps Gradle and the dependencies between local builds
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew installDist --no-daemon

FROM eclipse-temurin:24-jre
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home pouw
COPY --from=build /src/build/install/pouw-workload-generator /opt/pouw-workload
USER 10001
EXPOSE 8000
ENTRYPOINT ["/opt/pouw-workload/bin/pouw-workload-generator"]
