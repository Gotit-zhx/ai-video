FROM node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5
WORKDIR /app
ARG CLAUDE_VIDEO_COMMIT=83da59fa78c3eee9e20f515fe75c438bb5166efd
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ffmpeg git python3 \
    && git clone --filter=blob:none https://github.com/bradautomates/claude-video.git /tmp/claude-video \
    && git -C /tmp/claude-video checkout "$CLAUDE_VIDEO_COMMIT" \
    && mkdir -p /app/skills \
    && cp -R /tmp/claude-video/skills/watch /app/skills/watch \
    && cp /tmp/claude-video/LICENSE /app/skills/watch/LICENSE.upstream \
    && rm -rf /tmp/claude-video \
    && apt-get purge -y --auto-remove git \
    && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev --no-audit --no-fund --registry=https://registry.npmmirror.com
RUN cp /app/skills/watch/SKILL.md /app/skills/watch/UPSTREAM.md
COPY skills/watch/SKILL.md /app/skills/watch/SKILL.md
COPY src src
RUN python3 --version && ffmpeg -version >/dev/null && python3 /app/skills/watch/scripts/watch.py --help >/dev/null
RUN mkdir -p /data/image-operations && chown node:node /data/image-operations
USER node
EXPOSE 8090
CMD ["npm", "start"]
