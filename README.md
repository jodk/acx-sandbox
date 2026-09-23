# acx-sandbox

Java 版 **AI-Agent 沙箱平台**（openfuyao-sandbox / OpenKruise Agents 的纯 Java 精简实现）。在 Kubernetes 上提供多租户、秒级启动、可休眠、支持运行时动态挂载的 Agent 运行时。

核心能力：**① 资源池化+动态缩放的 Warm Pool、② 休眠/唤醒、③ 用户归属与路由寻址、④ CRD + E2B 双协议 API、⑤ agent-runtime 动态挂载（不重建 Pod）**。

| 平台组件 | Maven 模块 | 说明 |
|---|---|---|
| sandbox-controller | `acx-operator` | SandboxSet(池) / Sandbox(生命周期·休眠·调整) / SandboxClaim(认领) 三组 Reconciler；创建期注入 agent-runtime Sidecar |
| sandbox-manager | `acx-manager` | `/api` 管理 API、E2B `/sandboxes`、从池启动/认领、动态挂载编排、删除前挂载清理、路由同步；**内嵌前端控制台**（HTTP 8080） |
| sandbox-gateway | `acx-gateway` | Sandbox CR informer → 路由表；`/resolve` host 寻址（只解析不转发）；`/refresh` 对端同步 |
| agent-runtime | `acx-agent-runtime` | 沙箱 Pod 内特权 Sidecar，`/v1/mount|umount` 动态挂载执行端（非 Spring） |
| acx-frontend | `acx-frontend` | 静态 SPA 控制台（预热池/应用沙箱两个左菜单），作为 manager 的 classpath 静态资源 |

支撑模块：

| 模块 | 说明 |
|---|---|
| `acx-api` | `agents.kruise.io/v1alpha1` CRD 模型（Sandbox/SandboxSet/SandboxClaim/SandboxTemplate）与常量 |
| `acx-common` | `RouteStore`/`Route`、`HostRouter`、`K8sPeers`、`SandboxUtils` 状态推导 |

完整设计见 **[docs/design.md](docs/design.md)**（docs/ 下唯一文档，随最新代码维护：架构、能力、CRD 状态机、流程、REST API、部署、取舍）。

## 构建

```bash
export JAVA_HOME=/Volumes/d/jdk/current/Contents/Home
export PATH=/Volumes/d/maven/apache-maven-3.9.16/bin:$PATH
mvn clean install
```

主类：
- 控制器：`com.jodk.acx.operator.OperatorApplication`（shade uber-jar）
- 管理面：`com.jodk.acx.manager.ManagerApplication`（Spring Boot 8080，fat jar 含前端）
- 网关：`com.jodk.acx.gateway.GatewayApplication`（Spring Boot 8080）
- agent-runtime：`com.jodk.acx.agentruntime.AgentRuntimeMain`（JDK HttpServer 49983，`-all` uber-jar）

## 部署

一键部署：`bash deploy/remote-deploy.sh`（详见 docs/design.md §7）。

- CRD 清单：`config/crd/bases/`（4 个 `agents.kruise.io/v1alpha1` CRD；`config/` 目录现仅保留此子目录）。
- 组件清单 / Dockerfile / 一键脚本：`deploy/`（`deploy/k8s/*.yaml`、`deploy/docker/*`、`deploy.env`）。

## 纯 Java 移植与精简

- **memberlist gossip** → `K8sPeers`（Pod 列表 + label selector 发现对端）。
- **Envoy Go filter / ext_proc**（Go 网关数据面）→ `HostRouter` host 解析 + 路由注册表 + `/resolve`（不内嵌 Go filter，亦不做流量转发）。
- **node-agent / checkpoint / 快照恢复** → 已移除；休眠通过 controller 删除/重建底层 Pod（纯 K8s API）。
- **containerd/ttrpc、MinIO/S3、gRPC proto** → 随 node-agent/checkpoint 移除，依赖面收窄为 fabric8 + JOSDK + Spring Boot。
- **agent-runtime** → 由「CSI 请求构造库」升级为沙箱内特权 Sidecar：动态挂载（pv/host/tmpfs 多来源）在 Pod 内运行时执行，Pod 全程不重建。

## 尚未覆盖 / 已知问题

MCP API、admission webhook、深度休眠（checkpoint/快照）、Route B（节点级代理任意路径挂载）及偶发池自动消失问题，详见 docs/design.md §9。
