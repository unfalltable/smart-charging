FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY device-gateway/target/device-gateway-2.0.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 9000 9001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
