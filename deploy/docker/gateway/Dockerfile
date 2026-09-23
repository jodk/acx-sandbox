# acx-gateway (sandbox-gateway 流量路由面)
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="acx-sandbox-gateway"
LABEL org.opencontainers.image.description="OpenKruise Agents sandbox gateway routing plane (Java port)"
WORKDIR /app
COPY app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
