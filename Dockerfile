ARG RUNTIME_BASE=legacy-runtime
ARG JAR_SOURCE=build
FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS maven
FROM eclipse-temurin:21.0.9_10-jdk-noble@sha256:f3f6ba56a9ab68c1e99310d3b06bd1279ed03db00c581d8e22bb2e65d6ce8fa4 AS build
COPY --from=maven /usr/share/maven /usr/share/maven
ENV PATH=/usr/share/maven/bin:$PATH
WORKDIR /workspace
COPY pom.xml .
COPY maven-settings.xml .
COPY scripts/ci/VerifyDependencies.java scripts/ci/dependencies.sha256 scripts/ci/
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests clean package \
    && java scripts/ci/VerifyDependencies.java target/ai-commerce-platform-0.1.0-SNAPSHOT.jar scripts/ci/dependencies.sha256

FROM eclipse-temurin:21.0.9_10-jre-noble@sha256:d3eb69add1874bc785382d6282db53a67841f602a1139dee6c4a1221d8c56568 AS legacy-runtime
WORKDIR /app
COPY src/main/docker/runtime-packages.lock /tmp/runtime-packages.lock
COPY src/main/docker/runtime-bom.sha256 /tmp/runtime-bom.sha256
RUN sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update \
    && xargs -a /tmp/runtime-packages.lock apt-get install -y --no-install-recommends \
    && dpkg-query -W -f='${binary:Package}=${Version}\n' | LC_ALL=C sort -t= -k1,1 > /tmp/runtime-bom.txt \
    && cd /tmp && (sha256sum -c runtime-bom.sha256 || { diff -u runtime-packages.lock runtime-bom.txt; exit 1; }) \
    && rm -rf /var/lib/apt/lists/* /tmp/runtime-* \
    && useradd --system --uid 10001 spring \
    && mkdir -p /data/automation-skills /data/automation-attachments /data/video-scripts \
    && chown -R spring:spring /data/automation-skills /data/automation-attachments /data/video-scripts
RUN mkdir -p /data/ai-video-tasks && chown spring:spring /data/ai-video-tasks
FROM scratch AS compiled
COPY target/ai-commerce-platform-0.1.0-SNAPSHOT.jar /workspace/target/app.jar

FROM ${JAR_SOURCE} AS application
FROM ${RUNTIME_BASE}
WORKDIR /app
COPY --from=application /workspace/target/*.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
