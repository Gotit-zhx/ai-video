FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS maven
FROM eclipse-temurin:21.0.9_10-jdk-noble@sha256:f3f6ba56a9ab68c1e99310d3b06bd1279ed03db00c581d8e22bb2e65d6ce8fa4 AS build
COPY --from=maven /usr/share/maven /usr/share/maven
ENV PATH=/usr/share/maven/bin:$PATH
WORKDIR /workspace
COPY pom.xml .
COPY maven-settings.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests clean package

FROM eclipse-temurin:21.0.9_10-jre-noble@sha256:d3eb69add1874bc785382d6282db53a67841f602a1139dee6c4a1221d8c56568
WORKDIR /app
RUN sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 spring \
    && mkdir -p /data/automation-skills /data/automation-attachments /data/video-scripts \
    && chown -R spring:spring /data/automation-skills /data/automation-attachments /data/video-scripts
RUN mkdir -p /data/ai-video-tasks && chown spring:spring /data/ai-video-tasks
COPY --from=build /workspace/target/*.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
