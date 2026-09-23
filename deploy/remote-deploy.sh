#!/usr/bin/env bash
#
# 一键部署 acx-sandbox 到 10.0.33.113：
#   1) 本地 mvn 打包（fat/shaded jar）
#   2) 上传 jar 与 Dockerfile 到远端，远端 docker build + push Harbor
#   3) 上传部署清单，kubectl apply 到 sandbox-system 命名空间
#   4) 等待就绪并输出前端访问地址
#
# 前置：本地 mvn/sshpass/ssh/rsync；远端 root 可用密码登录且可访问集群(kubectl)与 Harbor。
# 可用环境变量覆盖默认值（见下方）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${DEPLOY_ENV_FILE:-${ROOT_DIR}/deploy/deploy.env}"

# ---- 默认参数（可用环境变量覆盖）----
: "${REMOTE_HOST:=10.0.33.113}"
: "${REMOTE_PORT:=22}"
: "${REMOTE_USER:=root}"
: "${REMOTE_PASSWORD:=Sugon@123qd}"
: "${REMOTE_DIR:=/opt/gridview/acx-sandbox}"

: "${IMAGE_REGISTRY:=image.ac.com:5000}"
: "${IMAGE_PROJECT:=acx}"
: "${IMAGE_TAG:=latest}"
: "${HARBOR_USERNAME:=admin}"
: "${HARBOR_PASSWORD:=Sugon@Harbor123}"

# 可选加载 deploy.env（Harbor/镜像相关）
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
  IMAGE_REGISTRY="${IMAGE_REGISTRY:-image.ac.com:5000}"
  HARBOR_USERNAME="${HARBOR_USERNAME:-admin}"
  HARBOR_PASSWORD="${HARBOR_PASSWORD:-}"
fi

export SSHPASS="${REMOTE_PASSWORD}"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "本地缺少命令：$1" >&2
    exit 1
  }
}
for c in mvn sshpass ssh rsync; do require_command "$c"; done

SSH_COMMON=(-p "${REMOTE_PORT}" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -o PreferredAuthentications=password -o PubkeyAuthentication=no -o NumberOfPasswordPrompts=1)

run_remote() { # 在远端执行一段命令（$1 为命令字符串）
  # shellcheck disable=SC2029
  sshpass -p "${REMOTE_PASSWORD}" ssh "${SSH_COMMON[@]}" "${REMOTE_USER}@${REMOTE_HOST}" "$1"
}
copy_to() { # 上传本地文件/目录到远端路径（$1 本地, $2 远端）
  sshpass -p "${REMOTE_PASSWORD}" rsync -az -e "ssh -p ${REMOTE_PORT} -o StrictHostKeyChecking=accept-new -o PreferredAuthentications=password -o PubkeyAuthentication=no -o NumberOfPasswordPrompts=1" "$1" "${REMOTE_USER}@${REMOTE_HOST}:$2"
}

REG_REPO="${IMAGE_REGISTRY}/${IMAGE_PROJECT}"
REMOTE_BASE="${REMOTE_DIR}"

# 四套运行镜像的 目录名/镜像名 映射
image_dir() {
  case "$1" in
    acx-sandbox-controller) echo "operator" ;;
    acx-sandbox-manager) echo "manager" ;;
    acx-sandbox-gateway) echo "gateway" ;;
    acx-agent-runtime) echo "agent-runtime" ;;
    *) echo "" ;;
  esac
}
# agent-runtime 需可执行 fat-jar（shade classifier=all）；其余模块用默认 jar
jar_path() {
  local img="$1" dir
  if [[ "$img" == "acx-agent-runtime" ]]; then
    echo "${ROOT_DIR}/acx-agent-runtime/target/acx-agent-runtime-1.0-SNAPSHOT-all.jar"
  else
    dir="$(image_dir "$img")"
    echo "${ROOT_DIR}/acx-${dir}/target/acx-${dir}-1.0-SNAPSHOT.jar"
  fi
}
ORDER_IMAGES=("acx-agent-runtime" "acx-sandbox-controller" "acx-sandbox-manager" "acx-sandbox-gateway")

echo "[1/6] 本地 mvn 打包（clean package）"
(cd "${ROOT_DIR}" && mvn -q clean package -DskipTests)

echo "[2/6] 上传 jar + Dockerfile 到 ${REMOTE_HOST}"
run_remote "mkdir -p ${REMOTE_BASE}/docker ${REMOTE_BASE}/docker/sbx-nginx ${REMOTE_BASE}/deploy/k8s ${REMOTE_BASE}/config/crd ${REMOTE_BASE}/config/docker"
for img in "${ORDER_IMAGES[@]}"; do
  dir="$(image_dir "$img")"
  jar="$(jar_path "$img")"
  [[ -f "${jar}" ]] || { echo "缺少 ${jar}" >&2; exit 1; }
  run_remote "mkdir -p ${REMOTE_BASE}/docker/${dir}"
  copy_to "${jar}" "${REMOTE_BASE}/docker/${dir}/app.jar"
  copy_to "${ROOT_DIR}/deploy/docker/${dir}/Dockerfile" "${REMOTE_BASE}/docker/${dir}/Dockerfile"
done
copy_to "${ROOT_DIR}/deploy/docker/sbx-nginx/Dockerfile" "${REMOTE_BASE}/docker/sbx-nginx/Dockerfile"

echo "[3/6] 远端构建并推送镜像到 ${REG_REPO}"
# Harbor 登录
run_remote "docker login ${IMAGE_REGISTRY} -u '${HARBOR_USERNAME}' -p '${HARBOR_PASSWORD}'"
for img in "${ORDER_IMAGES[@]}"; do
  dir="$(image_dir "$img")"
  echo "  - build/push ${REG_REPO}/${img}:${IMAGE_TAG}"
  run_remote "docker build -q -t ${REG_REPO}/${img}:${IMAGE_TAG} ${REMOTE_BASE}/docker/${dir} && docker push -q ${REG_REPO}/${img}:${IMAGE_TAG}"
done
# 演示沙箱基础镜像（nginx 两个版本，用于 In-Place 演示）
for nginx_tag in 1.27-alpine 1.25-alpine; do
  echo "  - build/push sbx-nginx:${nginx_tag}"
  run_remote "docker build -q --build-arg NGINX_TAG=${nginx_tag} -t ${REG_REPO}/sbx-nginx:${nginx_tag} ${REMOTE_BASE}/docker/sbx-nginx && docker push -q ${REG_REPO}/sbx-nginx:${nginx_tag}"
done

echo "[4/6] 上传部署清单"
run_remote "mkdir -p ${REMOTE_BASE}/deploy/k8s ${REMOTE_BASE}/config/crd/bases"
copy_to "${ROOT_DIR}/deploy/k8s/" "${REMOTE_BASE}/deploy/k8s/"
copy_to "${ROOT_DIR}/config/crd/bases/" "${REMOTE_BASE}/config/crd/bases/"

echo "[5/6] kubectl apply 到集群（sandbox-system）"
run_remote "
set -e
kubectl apply -f ${REMOTE_BASE}/deploy/k8s/00-namespace.yaml
kubectl create secret docker-registry regcred -n sandbox-system \
  --docker-server=${IMAGE_REGISTRY} \
  --docker-username='${HARBOR_USERNAME}' \
  --docker-password='${HARBOR_PASSWORD}' \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f ${REMOTE_BASE}/config/crd/bases/
kubectl apply -f ${REMOTE_BASE}/deploy/k8s/20-operator-rbac.yaml
kubectl apply -f ${REMOTE_BASE}/deploy/k8s/21-operator-deployment.yaml
kubectl apply -f ${REMOTE_BASE}/deploy/k8s/30-manager.yaml
kubectl apply -f ${REMOTE_BASE}/deploy/k8s/40-gateway.yaml
kubectl apply -f ${REMOTE_BASE}/deploy/k8s/50-nodeport.yaml
echo APPLY_DONE
"

echo "[6/7] 等待工作负载就绪"
run_remote "
set -e
kubectl -n sandbox-system rollout status deploy/sandbox-controller --timeout=180s
kubectl -n sandbox-system rollout status deploy/sandbox-manager    --timeout=180s
kubectl -n sandbox-system rollout status deploy/sandbox-gateway    --timeout=180s
kubectl -n sandbox-system get deploy,pods,svc
"

echo "[7/7] 在 ${REMOTE_HOST} 上建立 8080→manager 的访问代理"
# 注意：kill 与 start 必须分成两次独立的 ssh，否则 pkill 会匹配到同一远程 shell
# 命令行里那行真实的 "kubectl ... port-forward ..." 而把整个会话杀掉（exit 255）。
run_remote "pkill -f '[p]ort-forward svc/sandbox-manager' 2>/dev/null || true"
sleep 1
run_remote "setsid nohup kubectl -n sandbox-system port-forward svc/sandbox-manager 8080:8080 --address 0.0.0.0 </dev/null >/tmp/acx-port-forward.log 2>&1 & echo STARTED"
sleep 2
run_remote "curl -s -m 5 -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/health || echo NO_HEALTH"

# 输出访问地址：优先取一个 Ready 的 worker 节点 InternalIP
NODE_IP="$(run_remote "kubectl get nodes -o wide --no-headers 2>/dev/null | awk '\$2==\"Ready\" && \$3 !~ /control-plane/ {print \$6; exit}'")"
if [[ -z "${NODE_IP}" ]]; then
  NODE_IP="$(run_remote "kubectl get nodes -o wide --no-headers 2>/dev/null | awk '\$2==\"Ready\" {print \$6; exit}'")"
fi
NODE_IP="$(echo "${NODE_IP}" | tr -d '\r')"

echo
echo "================================================================"
echo "部署完成 ✅"
echo "  前端控制台: http://${NODE_IP}:30080/"
echo "  健康检查:   http://${NODE_IP}:30080/health"
echo "  命名空间:   sandbox-system"
echo "================================================================"
