FROM eclipse-temurin:21.0.9_10-jre-noble@sha256:d3eb69add1874bc785382d6282db53a67841f602a1139dee6c4a1221d8c56568
# The reviewed lock contains exact package=version lines captured from the accepted image.
COPY src/main/docker/runtime-packages.lock /tmp/runtime-packages.lock
COPY src/main/docker/runtime-bom.sha256 /tmp/runtime-bom.sha256
RUN sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update \
    && xargs -a /tmp/runtime-packages.lock apt-get install -y --no-install-recommends \
    && dpkg-query -W -f='${binary:Package}=${Version}\n' | LC_ALL=C sort -t= -k1,1 > /tmp/runtime-bom.txt \
    && cd /tmp && (sha256sum -c runtime-bom.sha256 || { diff -u runtime-packages.lock runtime-bom.txt; exit 1; }) \
    && rm -rf /var/lib/apt/lists/* /tmp/runtime-* \
    && useradd --system --uid 10001 spring \
    && mkdir -p /data/automation-skills /data/automation-attachments /data/video-scripts /data/ai-video-tasks \
    && chown -R spring:spring /data/automation-skills /data/automation-attachments /data/video-scripts /data/ai-video-tasks
WORKDIR /app
USER spring
