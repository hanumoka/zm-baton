# The zm-baton server with the Web screen built in, for running stage 1 on one PC
# (docs/stage1/sessions.md). Build context: apps/. No JDK, Node.js or Gradle is needed on the PC.

FROM node:24-bookworm-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk AS server
WORKDIR /server
COPY server/ ./
RUN ./gradlew bootJar --no-daemon --console=plain

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --home-dir /app zmbaton
WORKDIR /app
COPY --from=server /server/build/libs/server-0.0.1-SNAPSHOT.jar app.jar
COPY --from=web /web/dist/ web/
# Inside the container the server listens on all interfaces; compose publishes it on the
# PC's loopback only (127.0.0.1:18081), because there is no person authentication yet.
ENV ZM_BATON_WEB_DIR=file:/app/web/ \
    ZM_BATON_SERVER_ADDRESS=0.0.0.0
# The commit this image was built from, when the builder passes it (update.ps1 does); the export
# reports it. Last, so a new commit rebuilds only this layer.
ARG ZM_BATON_BUILD_COMMIT=unknown
ENV ZM_BATON_BUILD_COMMIT=${ZM_BATON_BUILD_COMMIT}
USER zmbaton
EXPOSE 18081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
