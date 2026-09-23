# acx-operator (sandbox-controller, JOSDK reconcilers)
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="acx-sandbox-controller"
LABEL org.opencontainers.image.description="OpenKruise Agents sandbox operator (Java port)"
WORKDIR /app
COPY app.jar /app/app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
