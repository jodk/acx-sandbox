# acx-sandbox 概要设计

**文档版本：** V1.2

**编制日期：** 2026-09-11

**项目标识：** acx-sandbox

**文档性质：** 软件概要设计说明书

**事实基线：** 当前工作区代码、Maven 配置、Kubernetes CRD/部署清单、测试与 docs/design.md。
**本次更新：** 按最新 lark-doc / lark-whiteboard 技能重新校验正文与可编辑画板，保留系统上下文、模块架构、Warm Pool 流程、动态挂载时序和部署拓扑五类图形。

> Java 版 AI-Agent 沙箱平台（openfuyao-sandbox / OpenKruise Agents 的纯 Java 精简实现）。
> 本文是 **docs/ 下唯一的设计文档**，按最新代码（2026-09-11）同步；此前分散的「agent-runtime 动态挂载实现分析与 E2E 日志」与「部署说明」已折入本文相应章节（§3.4、§5、§7），不再单独成文。
> 覆盖：架构 / 模块映射 / 核心能力 / CRD 模型与状态机 / 关键流程 / REST API / 部署 / 取舍与已知问题。

---

## 1. 定位与对外界面

在 Kubernetes 上提供**多租户、秒级启动、可休眠、支持运行时动态挂载**的 AI-Agent 沙箱：上层把沙箱当「云端环境」秒级领取，平台侧通过 CRD + 控制台管理池、生命周期与存储。

对外有三套界面：

| 面向人群 | 界面 | 入口 |
|---|---|---|
| 平台工程师 | Kubernetes CRD API（`agents.kruise.io/v1alpha1`） | `kubectl` / Kubernetes API |
| 平台用户 | 前端控制台（预热池 / 应用沙箱） | manager:8080（NodePort 30080），静态 SPA 内嵌于 manager fat jar |
| Agent / SDK | E2B 兼容 REST API | manager:8080 的 `/sandboxes`、`/health` 等 |

身份：HTTP 头 `X-API-KEY` 归一化为 owner 字符串（缺省 `anonymous`），写入 Sandbox 注解 `agents.kruise.io/owner`，用于列表/详情按归属过滤。**当前仅做归属不做鉴权**。

---

## 2. 总体架构

<whiteboard type="mermaid" path="@.feishu-diagrams/context.mmd"></whiteboard>

```
                         ┌───────────────────────────────────────────────────────────┐
                         │                          控制面 Control Plane              │
                         │                                                           │
   SRE / 平台工程师        │  acx-operator   ──►  JOSDK Reconciler×3                    │
   (CRD / kubectl) ──────▶│     SandboxSet(池) / Sandbox(Pod 生命周期) / SandboxClaim │
                          │  acx-manager     ──►  /api 管理 API + E2B /sandboxes       │
   平台用户 / Agent ─────▶│     claim / start / pause / inplace / 动态挂载 / 前端控制台  │
   (控制台 / SDK)          │              │   RouteStore 写 + K8sPeers fan-out /refresh │
                          └──────────────┼────────────────────────────────────────────┘
                                         ▼ 路由状态（Route resourceVersion CAS）
                         ┌───────────────────────────────────────────────────────────┐
                         │  acx-gateway（数据面“寻址”）                                 │
                         │    SandboxRouteSyncer（Sandbox CR informer）→ RouteStore    │
                         │    GET /resolve?host=… → {sandboxID, ip, port}（不做转发）    │
                         └───────────────────────────────────────────────────────────┘
   Sandbox 用户流量 ──► 按 /resolve 结果或业务自身端口直连 → Sandbox Pod
                         Pod 内含业务主容器 + agent-runtime Sidecar（动态挂载执行端）
```

- **控制面**：`acx-operator`（声明式 reconcile）+ `acx-manager`（命令式 API）。两者都作用在 Sandbox CR 及其底层 Pod 上。
- **数据面寻址**：`acx-gateway` 持有路由表（`RouteStore`），把 `<port>-<namespace>--<name>.<domain>` 这类 host 解析成 `Pod IP : port`。**它只解析不转发**；实际流量由调用方按解析结果（或业务自身暴露的服务）直连 Pod。
- 路由表两条维护路径：① gateway 的 `SandboxRouteSyncer` 以 informer 监听 Sandbox CR；② manager 在 claim/pause/resume/delete 等变更后写本地 `RouteStore` 并通过 `K8sPeers` 向对端广播 `POST /refresh`。路由条目以 `resourceVersion` 做 CAS 仲裁（`ResourceVersions.isResourceVersionNewer`），旧事件不覆盖新状态。

### 2.1 组件 → 模块映射

<whiteboard type="mermaid" path="@.feishu-diagrams/architecture.mmd"></whiteboard>

| 平台组件 | Maven 模块 | 主类 / 打包 | 职责 |
|---|---|---|---|
| sandbox-controller | `acx-operator` | `OperatorApplication` / shade uber-jar | SandboxSet(池) / Sandbox(Pod 生命周期·休眠·调整) / SandboxClaim(认领) 三组 Reconciler；`SidecarInjector` 创建期注入 agent-runtime |
| sandbox-manager | `acx-manager` | `ManagerApplication` / spring-boot fat jar（含前端） | `/api` 管理 API、E2B `/sandboxes`、从池启动/认领、动态挂载编排、删除前挂载清理、路由写 + 对端广播；托管前端静态资源 |
| sandbox-gateway | `acx-gateway` | `GatewayApplication` / spring-boot fat jar | Sandbox CR informer → 路由表；`GET /resolve` host 解析；`POST /refresh` 对端同步 |
| agent-runtime | `acx-agent-runtime` | `AgentRuntimeMain` / shade 出 `-all.jar`（thin jar 供 manager 复用） | 沙箱 Pod 内特权 Sidecar：`/v1/mount|umount` 执行动态挂载（非 Spring，JDK HttpServer） |
| acx-frontend | `acx-frontend` | 纯静态 jar | 控制台 SPA（预热池/应用沙箱两个左菜单），被 manager 依赖、从 `classpath:/static` 提供 |
| （支撑）CRD 模型 | `acx-api` | — | `agents.kruise.io/v1alpha1` 4 类 CR 模型、`ApiConstants`、`AgentRuntimeDefaults` |
| （支撑）共享逻辑 | `acx-common` | — | `RouteStore/Route/ResourceVersions`、`HostRouter`、`Peers/K8sPeers`、`SandboxUtils` 状态推导 |

### 2.2 模块依赖

```
acx-api ──► acx-common ──► acx-operator
                    └───► acx-gateway
acx-api ──► acx-agent-runtime
acx-manager ──► acx-api + acx-common + acx-agent-runtime + acx-frontend
acx-frontend：无依赖（静态 SPA jar）
```

---

## 3. 核心能力

### 3.1 Warm Pool（资源池化 + 动态缩放，秒级交付）

`SandboxSet` 维护一组**预创建、未认领**的 `Sandbox`；`SandboxSetReconciler` 按 `spec.replicas` 扩缩，把 Pod 创建耗时从请求链路中移除。

- 池内成员以 label 标记：`agents.kruise.io/sandbox-pool=<set>`、`agents.kruise.io/sandbox-claimed=false`。
- 扩缩：列出 `claimed=false` 的空闲成员，少于 `replicas` 补建（名 `generateName=<set>-`），多于则删除。
- SandboxSet 的 `host-mounts`、`dynamic-roots` 注解会在建成员时透传给每个 Sandbox。
- 空闲成员被 app 领取后即「脱离池」（移除 `sandbox-pool` label 与 SandboxSet ownerRef），不再被池计数/回收。

### 3.2 休眠 / 唤醒（Hibernation）

- `Sandbox.spec.paused=true` → `SandboxReconciler` **删除底层 Pod**（释放节点资源），phase=`Paused`；`paused=false` → 按模板重建 Pod → Ready → `Running`。
- 超时经 `spec.shutdownTime` 表达，到期 reconcile 直接删除该 Sandbox。
- 本版休眠为 **Pod 级删除/重建**（纯 K8s API，无节点 Agent）；不做 kata/checkpoint 级冻结（见 §9）。

### 3.3 用户身份与归属

- `X-API-KEY` → manager 归一化 owner（缺省 `anonymous`），写 Sandbox 注解 `agents.kruise.io/owner`，并作为 Route.owner / E2B clientID。
- E2B 的 list/get 只返回属于该 owner 的沙箱。

### 3.4 动态挂载（Route A：agent-runtime Sidecar，不重建 Pod）

K8s 卷在 Pod 创建后不可变，因此“给运行中容器加卷”通过 **Pod 内特权 Sidecar 运行时执行挂载**实现，Pod spec 全程不变：

- **创建期注入（operator `SidecarInjector`）**：`Sandbox.spec.runtimes` 含 `agent-runtime` 时，在 `generatePodFromSandbox` 中对 PodSpec **深拷贝**后注入：
  - 共享 `emptyDir` `agent-runtime-share`（Sidecar 挂 `/mnt/envd`，`mountPropagation: Bidirectional`；业务主容器挂 `/mnt/envd` 或用户 `dynamic-roots`，`HostToContainer`）；
  - 特权 `agent-runtime` Sidecar（镜像取 operator env `AGENT_RUNTIME_IMAGE`，缺省 `image.ac.com:5000/acx/acx-agent-runtime:latest`）；
  - `hostMounts`（池注解 JSON 数组）→ 生成 `acx-hostmount-<i>` hostPath 卷，**只挂进 Sidecar 同一绝对路径**（业务容器不可见），作为 bind 动态挂载的宿主源；
  - 主容器 env `ACX_MOUNT_ROOT`：缺省布局 `/mnt/envd/volumes`；配置 `dynamicRoots` 时只挂这些根且去掉默认 `/mnt/envd`，`ACX_MOUNT_ROOT=<首根>/volumes`。
- **控制面（manager `DynamicMountService`）**：`POST /api/sandboxes/{id}/dynamic-mounts` 按 `source` 分发（缺省按 `pvName` 判定为 `pv`）：

  | source | 必填 | 语义 / 执行器 |
  |---|---|---|
  | `pv` | `pvName` | 读 CSI PV → `CsiNodePublishRequestBuilder.build(...)`，driver 由 `pv.spec.csi.driver` 归一化（bind / tmpfs） |
  | `host` | `path` | 无需 PV：bind 该沙箱 `hostMounts` 已注入的宿主根及子路径（越权 400）；缺目录自动 `mkdir -p` |
  | `tmpfs` | 可选 `sizeMi` | 无需 PV：`mount -t tmpfs -o size=…M` 匿名内存盘 |

- **执行端（agent-runtime）**：`AgentRuntimeMain`（JDK `HttpServer` 0.0.0.0:49983）`POST /v1/mount|umount`；`MountRegistry` 默认注册 `tmpfs`/`bind` 两个 `MountExecutor`；`AbstractMountExecutor` 统一 `mkdir -p target` → `mount` → 登记 `MountTracker`（落盘共享卷 `.acx-mounts.json`）；umount 失败回退 `umount -l`；JVM shutdown hook 兜底卸载。
- **通道**：manager `RuntimeMountClient` SPI（Route B 预留口），默认 `SidecarRuntimeMountClient` 用 fabric8 `port-forward Pod:49983` → `HttpClient` POST。
- **挂载登记**：Sandbox 注解 `agents.kruise.io/active-mounts`（JSON 数组：mountId/driver/source/subPath/readOnly/containerPath/podUid/mountedAt），UI 展示并可卸载。
- **泄漏清理**：manager `SandboxMountReaper` 在**删除沙箱/池前**逐个 umount `active-mounts`；operator 的 `SandboxReconciler.cleanup` 只删 Pod。
- **可见性约束**：业务容器不特权 ⇒ 动态挂载只在共享传播子树（`ACX_MOUNT_ROOT` 下）可见；任意路径需 envd 式或 Route B。

### 3.5 前端控制台

纯原生 JS 单页（`acx-frontend/src/main/resources/static/{index.html,css/style.css,js/app.js}`），左侧两个菜单：**预热池**（建池/详情/扩缩容/删除/从池启动）、**应用沙箱**（详情/暂停恢复/换镜像/追加挂载/动态挂载卸载）。`app.js` 每 5s 轮询 `/api/pools` + `/api/sandboxes` 与 `/health`。

---

## 4. CRD 数据模型与状态机

### 4.1 资源（`agents.kruise.io/v1alpha1`，Namespaced，shortName sbx/sbs/sbc/sbt）

| CRD | 作用 | 关键字段 | 控制器 |
|---|---|---|---|
| `SandboxTemplate` | 只读模板（Pod 模板） | `spec.template`、`runtimes` | **无独立 reconciler**（仅 `OperatorSupport.resolveTemplate` 读取） |
| `SandboxSet` | 沙箱池 | `spec.replicas`、`spec.template/templateRef`、`runtimes`、注解 `host-mounts`/`dynamic-roots`；`status.replicas/availableReplicas/selector` | SandboxSetReconciler |
| `Sandbox` | 一个沙箱实例（核心单元） | `spec.paused`、`shutdownTime`、`template`、`runtimes`；`status.phase/conditions[Ready]/podInfo/nodeName/sandboxIp` | SandboxReconciler（Cleaner + finalizer `agents.kruise.io/sandbox`） |
| `SandboxClaim` | 批量认领（面向 CRD 用户） | `spec.templateName/replicas/shutdownTime`（当前实现只用这三个） | SandboxClaimReconciler |

### 4.2 沙箱状态

- `status.phase`（`SandboxPhase`）：`Pending / Running / Paused / Resuming / Succeeded / Failed / Terminating / Restoring`。
- 对外 `state`（`SandboxUtils.getSandboxState` 推导）：

| state | 语义 | 推导依据 |
|---|---|---|
| `creating` | 建/认领中，Pod 未 Ready | 未受控且未 Ready |
| `available` | **池内空闲可领** | 受 SandboxSet 控制 且 Ready 且 `claimed=false` |
| `running` | 已领且运行 | `claimed=true`、Ready、未暂停 |
| `paused` | 已休眠 | `spec.paused=true` 或 phase=Paused |
| `dead` | 已删 / 超时 / 失败 | deletionTimestamp / 超 shutdownTime / Failed |

```
                SandboxSet 池扩缩
   creating ───────────────► available ──(认领 claim)──► running
      ▲                        │  ▲                        │
      │                        │  │                        │ pause / resume
      └────(重建 Pod)──────────┘  └────────────────────────► paused
   running / paused / available ──(删除/超时)──────────────► dead
```

### 4.3 关键 label / 注解

- Label：`sandbox-pool`、`sandbox-template`、`sandbox-claimed`（**claimed 是 label**）、`claim-name`。
- 注解：`owner`、`claim-timestamp`、`adjust`（`inplace`/`rebuild`）、`active-mounts`、`host-mounts`、`dynamic-roots`、`runtime-url`/`runtime-access-token`、E2B `e2b.agents.kruise.io/envd-url`。

---

## 5. 关键流程

### 5.1 建池（Warm Pool）

<whiteboard type="mermaid" path="@.feishu-diagrams/pool-sequence.mmd"></whiteboard>

```
POST /api/pools {name,image,replicas,runtimes:[{name:agent-runtime}],hostMounts,dynamicRoots,mounts…}
  └─ AdminService.createPool → 建 SandboxSet（annotations 写 host-mounts/dynamic-roots）
        └─ SandboxSetReconciler：补建 claimed=false 的 Sandbox（透传注解/runtimes/template）
              └─ SandboxReconciler + SidecarInjector：生成含 agent-runtime Sidecar 的 Pod 并拉起
```

### 5.2 从池启动（领取沙箱，秒级）

```
POST /api/pools/{pool}/start {image,command,mounts,env,…}
  └─ AdminService.startFromPool：取该池 claimed=false 的沙箱
       · 置 claimed=true + 移除 sandbox-pool label / SandboxSet ownerRef（脱离池）
       · 覆盖 image/command/mounts/env 等 template
       · 仅镜像变化 → adjust=inplace（就地换镜像，Pod 不重建）
       · 含挂载等 spec 变更 → adjust=rebuild（重建 Pod）
       └─ SandboxReconciler 执行对应调整 → Ready
```

### 5.3 E2B 认领

```
POST /sandboxes {templateID, timeout, metadata}
  └─ SandboxManager.claimSandbox：按 sandbox-template=<id> + claimed=false 取候选
       · 打 claimed=true / owner / claim-timestamp / metadata
       · syncRoute → 本地 RouteStore + K8sPeers 广播 /refresh
       · 返回 SandboxDto{sandboxID: <ns>--<name>, state, …}
```

### 5.4 SandboxClaim CRD 批量认领

用户直接建 `SandboxClaim`：`SandboxClaimReconciler` 按 `sandbox-template` + `claimed=false` 逐个认领到 `spec.replicas`，全部认领后置 `phase=Completed`。

### 5.5 休眠 / 唤醒

```
POST /sandboxes/{id}/pause|resume   （或管理面 POST /api/sandboxes/{id}/pause|resume）
  └─ manager 置 spec.paused → SandboxReconciler 删 Pod 置 Paused / 重建 Pod 置 Running
```

### 5.6 调整（inplace / rebuild）

- `POST /api/sandboxes/{id}/inplace {image}` → manager 改 template 首容器镜像 + 写 `adjust=inplace` → operator 就地 patch 运行中 Pod 镜像（kubelet 重启容器，Pod UID/IP 不变）。
- `POST /api/sandboxes/{id}/mounts {mounts[]}`（emptyDir/configMap/pvc/hostPath）→ manager 改 template + 写 `adjust=rebuild` → operator 删旧 Pod 重建（属静态挂载，需重建）。

### 5.7 动态挂载 / 卸载（不重建 Pod）

<whiteboard type="mermaid" path="@.feishu-diagrams/mount-sequence.mmd"></whiteboard>

```
POST /api/sandboxes/{id}/dynamic-mounts {source,pvName/path/sizeMi,subPath,readOnly}
  └─ DynamicMountService：按 source 构造请求 → SidecarRuntimeMountClient port-forward Pod:49983
        └─ agent-runtime /v1/mount → AbstractMountExecutor(mkdir→mount→tracker.persist)
        └─ 成功 → Sandbox 注解 active-mounts 追加一条（Pod spec 未动）

POST /api/sandboxes/{id}/dynamic-umount {mountId}
  └─ DynamicMountService：查注解 → /v1/umount → agent-runtime umount(+lazy 回退) → 注解移除
```

### 5.8 删除与挂载清理

```
DELETE /api/sandboxes/{id} / DELETE /api/pools/{name}
  └─ SandboxMountReaper.umountAll：逐个 /v1/umount active-mounts（防节点挂载泄漏）
        └─ 删除 Sandbox / SandboxSet → SandboxReconciler.cleanup 删同名 Pod → 移除 finalizer
  （agent-runtime JVM shutdown hook 兜底卸载）
```

### 5.9 流量寻址（gateway）

```
请求 Host: 3000-ns--sandbox1.example.com
  └─ gateway GET /resolve?host=…  → HostRouter 提取 (sandboxId, port)
       · RouteStore.loadRoute(sandboxId)：state=running → {sandboxID, ip, port}（200）
       · state≠running → 502 sandbox_not_running；未命中 → 404 sandbox_not_found；host 非法 → 400
```

---

## 6. REST API 速查

### 管理面 `/api`（`ApiController`，owner 经 `X-API-KEY`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/api/pools` | 列池 / 建池 |
| POST | `/api/pools/{pool}/scale` | 扩缩容 `replicas` |
| DELETE | `/api/pools/{pool}` | 删池（先清理池内空闲动态挂载） |
| GET | `/api/pvs` | 列出 CSI PV（动态挂载候选） |
| GET | `/api/sandboxes` | 列沙箱（管理视图） |
| GET | `/api/sandboxes/{sandboxId}` | 沙箱详情 |
| POST | `/api/pools/{pool}/start` | 从池启动应用沙箱 |
| POST | `/api/sandboxes/{id}/mounts` | 追加静态挂载（重建 Pod） |
| POST | `/api/sandboxes/{id}/dynamic-mounts` | 动态挂载（不重建） |
| POST | `/api/sandboxes/{id}/dynamic-umount` | 动态卸载 |
| POST | `/api/sandboxes/{id}/inplace` | 原地换镜像 |
| POST | `/api/sandboxes/{id}/pause` / `resume` | 休眠 / 唤醒 |
| DELETE | `/api/sandboxes/{id}` | 删沙箱 |

### E2B 兼容（`E2bController`）

`GET /health`、`POST/GET /sandboxes`、`GET/DELETE /sandboxes/{id}`、`POST /sandboxes/{id}/pause|resume|connect|timeout`、`GET /debug`（路由表）。

### gateway

`GET /health`、`GET /resolve?host=…`、`GET /routes`、`POST /refresh`。manager 侧亦有 `POST /refresh` 接收对端路由同步。

---

## 7. 部署

### 7.1 一键部署

<whiteboard type="mermaid" path="@.feishu-diagrams/deployment.mmd"></whiteboard>

`bash deploy/remote-deploy.sh`（需 JDK25 / Maven / sshpass / ssh / rsync；远端 root 密码与 Harbor 凭据见 `deploy/deploy.env`）：

1. 本地 `mvn clean package`；
2. 上传各 jar + `deploy/docker/<模块>/Dockerfile` 到远端 `/opt/gridview/acx-sandbox/docker/`（agent-runtime 用 `-all.jar`）；
3. 远端 docker build + push Harbor `image.ac.com:5000/acx/acx-{agent-runtime,sandbox-controller,sandbox-manager,sandbox-gateway}:latest`，另构建演示镜像 `sbx-nginx:1.27-alpine`/`1.25-alpine`；
4. 上传清单并 apply：`deploy/k8s/`（00 命名空间 → regcred → **CRD `config/crd/bases/`（4 个）** → 20 operator RBAC → 21 operator Deployment → 30 manager → 40 gateway → 50 NodePort）—— CRD 是 config/ 目录唯一保留内容；
5. rollout 等待三个 Deployment Ready；
6. 远端 `kubectl port-forward svc/sandbox-manager 8080:8080 --address 0.0.0.0` 建立访问代理。

### 7.2 集群环境事实（重要）

- kubectl `https://10.0.31.15:56443`，Service ClusterIP 网段 `10.68.0.0/16`。
- 节点分两个网络组，**跨组 Pod 网络不通**：`10.0.31.15/.23` 可达 apiserver；`10.0.33.117/242/243` 与 apiserver 隔离（informer 10s 超时）。故三个控制面 Deployment 均 `nodeAffinity` 钉到 `10.0.31.15/10.0.31.23`；沙箱 Pod 不要求直连 apiserver，可调度任意节点。
- **fabric8 必须 ≥ 7.3.0**（根 pom 固定 7.3.1）：Jackson 2.19 + fabric8 7.1.0 对 `managedFields[].fieldsV1` 序列化抛 `keySerializer is null`，导致 JOSDK reconcile 反复失败。
- 镜像 tag 恒 `latest` 时 `kubectl apply` 不触发滚动，迭代需手动 `kubectl -n sandbox-system rollout restart deploy/sandbox-{controller,manager,gateway}`（imagePullPolicy=Always）。

### 7.3 访问地址

| 入口 | URL | 说明 |
|---|---|---|
| 前端控制台 | `http://10.0.33.113:8080/` | 远端 port-forward → manager:8080 |
| NodePort | `http://10.0.31.15:30080/` 或 `http://10.0.31.23:30080/` | `sandbox-manager-nodeport` |
| 健康检查 | `http://10.0.33.113:8080/health` | manager /health |

---

## 8. 技术栈与构建

| 领域 | 选型 |
|---|---|
| 语言 / 运行 | Java 25、Spring Boot 3.5.0（仅 manager/gateway） |
| K8s 客户端 | fabric8 kubernetes-client 7.3.1 |
| Operator | Java Operator SDK 5.0.0（`Reconciler<T>` / `Cleaner<T>`） |
| agent-runtime | 非 Spring，JDK `com.sun.net.httpserver` + 虚拟线程 |
| 构建 | Maven 多模块（reactor，模块序 api→common→operator→frontend→manager→gateway→agent-runtime） |

打包：operator 用 shade 打 uber-jar；manager/gateway 用 spring-boot repackage；agent-runtime 用 shade 出可执行 `-all.jar` 且保留 thin jar（供 manager 复用 `storages`/POJO）；frontend 为纯静态 jar 内嵌进 manager fat jar。

---

## 9. 取舍 / 未实现 / 已知问题

**相对 Go 原版的裁剪**（`acx-common`/`deploy` 无 node-agent/checkpoint/proto/ext_proc）：休眠=Pod 删除重建；路由用 `HostRouter` + `/resolve`（非 Envoy Go filter）；对端发现用 `K8sPeers`（非 memberlist）。

**明确未实现 / 预留**：
- MCP API、admission webhook。
- `SandboxTemplate` 无独立 controller；`SandboxClaimSpec` 的 `inplaceUpdate/dynamicVolumesMount/runtimes/createOnNoStock/waitReadyTimeout` 等字段当前实现未消费（只用到 `templateName/replicas/shutdownTime`）。
- `X-API-KEY` 只做 owner 归属，不做鉴权（`e2b-key-store` Secret/Role 存在但代码未读）。
- gateway 不做反向代理/流量转发，寻址结果需配合部署侧 L4/L7 与业务自身暴露端口。
- NFS 动态挂载已整体移除：不再有 `source=nfs`（`mount -t nfs server:path`、无需 PV）来源，`MountRegistry` 与 PV driver 归一化也不再识别 nfs 驱动（执行端镜像亦不再预装 `nfs-common`）。如需复用 NFS 存储，只能由节点预先挂好 NFS 并把该宿主根声明进池 `hostMounts`，经 `host`/`bind` 动态挂载引入 —— 属节点级 NFS 复用，平台自身不负责发起 NFS 挂载。
- Route B（节点级代理做任意路径/通用 CSI）：仅预留接口（manager `RuntimeMountClient` SPI、sidecar `MountExecutor` SPI），不实现。

**已知问题**：
- 偶发：建池后 SandboxSet 曾被观测到数十秒后自动消失（当时成员 Pod 未创建），根因未明、待复现排查（遗留 issue）。
- 节点曾出现动态挂载泄漏的孤儿挂载/卡住 Pod（已手工清理；泄漏防护为 §5.8 reaper + shutdown hook，后续仍需观察）。
