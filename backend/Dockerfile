# Sprint 6 — packaging the app as a container image.
#
# MULTI-STAGE BUILD. Stage 1 has the JDK, Gradle, and every dependency needed
# to compile; stage 2 has only a JRE and the finished jar. The build tools
# never reach the shipped image, which makes it smaller, faster to pull, and
# far smaller as an attack surface — a compiler in production is a gift to
# anyone who gets a shell.

# ---------- stage 1: build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy ONLY the build definition first. Docker caches each layer, so as long
# as these files don't change, the (slow) dependency download below is reused
# from cache even when application code changes. Copying everything up front
# would invalidate that cache on every source edit.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Now the source, which changes often.
COPY src ./src
# -x test: images are built from code that ALREADY passed CI. Running tests
# here would need Docker-inside-Docker for Testcontainers and would double the
# build time for no extra safety.
RUN ./gradlew bootJar --no-daemon -x test

# ---------- stage 2: run ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Never run as root. If the app is ever compromised, the attacker inherits
# this user's (minimal) privileges rather than the machine's.
RUN useradd --system --create-home --uid 10001 nowserving
USER nowserving

COPY --from=build --chown=nowserving:nowserving /app/build/libs/*.jar app.jar

EXPOSE 8080

# Container-aware JVM flags: without MaxRAMPercentage the JVM sizes its heap
# from the HOST's memory and gets OOM-killed the moment Kubernetes enforces a
# smaller container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

# exec form + `exec` so the JVM becomes PID 1 and receives SIGTERM directly.
# Without it, Kubernetes' graceful shutdown signal goes to a shell that
# ignores it, and pods get force-killed mid-request every deploy.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
