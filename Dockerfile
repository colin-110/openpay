# One Dockerfile for every service in the monorepo.
#
# The build stage is identical for all of them, so Docker's layer cache builds the reactor once and
# every subsequent image reuses it. Only the final COPY differs, which is why MODULE and ARTIFACT
# are declared in the runtime stage rather than at the top: an ARG invalidates every layer after
# it, and declaring them early would rebuild the whole project nine times.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Poms first. Dependencies change far less often than source, so this layer survives most edits and
# saves re-downloading the world on every code change.
COPY pom.xml ./
COPY libs/common-observability/pom.xml libs/common-observability/
COPY libs/common-security/pom.xml libs/common-security/
COPY libs/common-kafka/pom.xml libs/common-kafka/
COPY libs/common-outbox/pom.xml libs/common-outbox/
COPY libs/common-audit/pom.xml libs/common-audit/
COPY services/gateway-service/pom.xml services/gateway-service/
COPY services/auth-service/pom.xml services/auth-service/
COPY services/merchant-service/pom.xml services/merchant-service/
COPY services/payment-service/pom.xml services/payment-service/
COPY services/provider-router-service/pom.xml services/provider-router-service/
COPY services/webhook-service/pom.xml services/webhook-service/
COPY services/ledger-service/pom.xml services/ledger-service/
COPY services/settlement-service/pom.xml services/settlement-service/
COPY services/notification-service/pom.xml services/notification-service/
COPY services/fraud-service/pom.xml services/fraud-service/
COPY services/mock-bank-service/pom.xml services/mock-bank-service/
COPY services/vault-service/pom.xml services/vault-service/
COPY services/demo-storefront/pom.xml services/demo-storefront/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY libs libs
COPY services services
# Tests are the build pipeline's job, not the image's. Running them here would need a Docker daemon
# inside the build for Testcontainers, and would repeat on every image.
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre AS runtime

# Which module this image runs. Declared here so the build stage above stays shared and cached.
ARG MODULE
ARG ARTIFACT

# curl is here for the container healthcheck, which hits /actuator/health rather than just probing
# the port. A port that is open says the JVM started; it says nothing about whether the service can
# reach its database.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Runs unprivileged: a payment service that is compromised should not also be root in its
# container. Pinned to a fixed numeric uid/gid, not just a named user: Kubernetes' runAsNonRoot
# check has to verify the *number* a container will actually run as before it starts anything,
# and a named USER it cannot resolve to one is a refusal to start rather than an assumption in
# the container's favour. 10001 is arbitrary but fixed, so platform/k8s/30-services.yaml can
# state the same number in runAsUser rather than asking Kubernetes to trust the image.
RUN groupadd --system --gid 10001 openpay && useradd --system --uid 10001 --gid openpay --home /app openpay
WORKDIR /app

COPY --from=build /build/${MODULE}/target/${ARTIFACT}-*.jar /app/app.jar
RUN chown -R openpay:openpay /app
USER 10001:10001

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then respects whatever the container is given,
# so changing the compose memory limit does not silently leave the heap wrong.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
