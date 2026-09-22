FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY platform-contracts/pom.xml platform-contracts/pom.xml
COPY platform-core/pom.xml platform-core/pom.xml
COPY device-gateway/pom.xml device-gateway/pom.xml
COPY platform-contracts/src platform-contracts/src
COPY platform-core/src platform-core/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl platform-core -am -DskipTests package && \
    cp platform-core/target/platform-core-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=12 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
