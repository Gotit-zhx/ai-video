FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY maven-settings.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -s maven-settings.xml clean package

FROM eclipse-temurin:21-jre
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
