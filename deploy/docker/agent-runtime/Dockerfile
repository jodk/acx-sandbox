# acx-agent-runtime (envd 兼容运行时 Sidecar，Java 实现)
# 注入 Sandbox Pod 提供：命令/文件操作(预留)、动态 CSI 挂载(/v1/mount、/v1/umount)、lifecycle hooks。
# 动态挂载需要在容器内执行 mount(2)：privileged 提供 CAP_SYS_ADMIN。
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="acx-agent-runtime"
LABEL org.opencontainers.image.description="OpenKruise Agents sandbox agent-runtime sidecar (Java port)"
WORKDIR /app
COPY app.jar /app/app.jar
EXPOSE 49983
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
