# acx-manager (sandbox-manager 控制面 REST API + 管理前端 SPA)
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="acx-sandbox-manager"
LABEL org.opencontainers.image.description="OpenKruise Agents sandbox control plane (Java port)"
WORKDIR /app
COPY app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
