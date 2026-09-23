/* ACX Sandbox 管理控制台 —— 纯静态 SPA，无构建工具 */
'use strict';

// ---------------------------------------------------------------------------
// 基础工具
// ---------------------------------------------------------------------------
const $ = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

async function api(path, opts) {
  const init = Object.assign({ headers: { 'Content-Type': 'application/json' } }, opts || {});
  if (init.body && typeof init.body !== 'string') init.body = JSON.stringify(init.body);
  let res;
  try {
    res = await fetch('/api' + path, init);
  } catch (e) {
    throw new Error('无法连接后端: ' + e.message);
  }
  if (res.status === 204) return null;
  let data = null;
  try { data = await res.json(); } catch (e) { /* ignore */ }
  if (!res.ok) {
    const msg = data && data.error ? data.error : ('HTTP ' + res.status + ' ' + res.statusText);
    throw new Error(msg);
  }
  return data;
}

function toast(msg, type) {
  const area = $('#toastArea');
  const el = document.createElement('div');
  el.className = 'toast ' + (type || '');
  el.textContent = msg;
  area.appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; }, 3800);
  setTimeout(() => el.remove(), 4200);
}

// 共享态
const state = { pools: [], sandboxes: [], timer: null };

// ---------------------------------------------------------------------------
// 弹窗
// ---------------------------------------------------------------------------
function openModal(title, bodyEl) {
  const mask = $('#modalMask');
  const box = $('#modalBox');
  box.innerHTML = '';
  const head = document.createElement('div');
  head.className = 'modal-head';
  head.innerHTML = '<h3>' + esc(title) + '</h3>';
  const closeBtn = document.createElement('button');
  closeBtn.className = 'close';
  closeBtn.innerHTML = '&times;';
  closeBtn.onclick = closeModal;
  head.appendChild(closeBtn);
  box.appendChild(head);

  const body = document.createElement('div');
  body.className = 'modal-body';
  body.appendChild(bodyEl);
  box.appendChild(body);
  mask.hidden = false;
  return { mask, box, body, close: closeModal };
}

function closeModal() { $('#modalMask').hidden = true; }
$('#modalMask') && $('#modalMask').addEventListener('click', (e) => {
  if (e.target.id === 'modalMask') closeModal();
});

function modalFoot(buttons) {
  const foot = document.createElement('div');
  foot.className = 'modal-foot';
  for (const b of buttons) {
    const btn = document.createElement('button');
    btn.className = 'btn ' + (b.cls || '');
    btn.textContent = b.label;
    btn.onclick = () => b.onClick(btn);
    foot.appendChild(btn);
  }
  return foot;
}

function buttonBusy(btn, busy, label) {
  if (busy) { btn.dataset.label = label || btn.textContent; btn.textContent = '处理中…'; btn.disabled = true; }
  else { btn.textContent = btn.dataset.label || label || '确定'; btn.disabled = false; }
}

function field(label, inputEl, hint) {
  const wrap = document.createElement('div');
  wrap.className = 'field';
  const l = document.createElement('label');
  l.textContent = label;
  wrap.appendChild(l);
  wrap.appendChild(inputEl);
  if (hint) { const h = document.createElement('div'); h.className = 'hint'; h.innerHTML = hint; wrap.appendChild(h); }
  return wrap;
}

function textInput(placeholder, value) {
  const el = document.createElement('input');
  el.type = 'text';
  el.placeholder = placeholder || '';
  if (value != null) el.value = value;
  return el;
}
function numInput(placeholder, value, min) {
  const el = document.createElement('input');
  el.type = 'number';
  el.placeholder = placeholder || '';
  if (value != null) el.value = value;
  if (min != null) el.min = min;
  return el;
}

// ---------------------------------------------------------------------------
// 数据渲染
// ---------------------------------------------------------------------------
function chip(stateName) {
  const cls = ['available', 'running', 'creating', 'paused', 'dead'].includes(stateName) ? stateName : 'unknown';
  return '<span class="state ' + cls + '">' + esc(stateName) + '</span>';
}

/** Whether the sandbox pod is running a live agent-runtime sidecar that accepts dynamic mounts. */
function canDynMount(s) {
  return !!(s && s.agentRuntime && !s.paused && (s.state === 'running' || s.state === 'available'));
}

/**
 * 操作列的“挂载”入口：agent-runtime 沙箱只暴露不重建 Pod 的动态 CSI 挂载；
 * 非 agent-runtime 沙箱（无 Sidecar）才保留需重建 Pod 的模板挂载；
 * agent-runtime 但当前不可挂载（创建中/暂停/dead）时不显示任何挂载按钮。
 */
function mountActionButtons(s) {
  if (canDynMount(s)) {
    return '<button class="btn sm primary act-dyn" title="CSI 动态挂载：Sidecar 挂载进共享目录，不重建 Pod">动态挂载</button>';
  }
  if (!s.agentRuntime) {
    return '<button class="btn sm act-mount" title="该沙箱无 agent-runtime，只能重建 Pod 使新挂载生效">挂载</button>';
  }
  return '';
}

// ---------------------------------------------------------------------------
// 左侧菜单视图切换
// ---------------------------------------------------------------------------
let activeView = 'pools';

function showView(view) {
  activeView = view;
  $('#navPools').classList.toggle('active', view === 'pools');
  $('#navSandboxes').classList.toggle('active', view === 'sandboxes');
  $('#poolsView').hidden = view !== 'pools';
  $('#sandboxesView').hidden = view !== 'sandboxes';
}

function renderPools() {
  $('#poolCount').textContent = state.pools.length;
  $('#navPoolCount').textContent = state.pools.length;
  const rows = $('#poolRows');
  rows.innerHTML = '';
  const empty = $('#poolEmpty');
  empty.hidden = state.pools.length > 0;
  for (const p of state.pools) {
    const tr = document.createElement('tr');
    tr.innerHTML =
      '<td><b>' + esc(p.name) + '</b>' +
        (p.agentRuntime ? '<span title="已启用 agent-runtime 动态挂载" style="margin-left:6px;font-size:10px;padding:1px 6px;border:1px solid var(--line);border-radius:8px;color:var(--green);">dynamic</span>' : '') +
        (p.hostMounts && p.hostMounts.length ? '<span title="hostMounts: ' + esc(p.hostMounts.join('\n')) + '" style="margin-left:6px;font-size:10px;padding:1px 6px;border:1px solid var(--line);border-radius:8px;color:var(--amber);">host</span>' : '') +
        (p.dynamicRoots && p.dynamicRoots.length ? '<span title="dynamicRoots: ' + esc(p.dynamicRoots.join('\n')) + '" style="margin-left:6px;font-size:10px;padding:1px 6px;border:1px solid var(--line);border-radius:8px;color:#4da3ff;">roots</span>' : '') +
      '</td>' +
      '<td>' + p.replicas + '</td>' +
      '<td class="mono" style="color:var(--green)">' + p.available + '</td>' +
      '<td class="mono" style="color:var(--amber)">' + p.creating + '</td>' +
      '<td class="mono">' + p.claimed + '</td>' +
      '<td class="mono truncate" title="' + esc(p.image) + '">' + esc(p.image || '-') + '</td>' +
      '<td><div class="actions">' +
        '<button class="btn primary sm act-start">启动</button>' +
        '<button class="btn sm act-detail" title="查看预热池详情与池内沙箱">详情</button>' +
        '<button class="btn sm act-minus" title="减少副本">－</button>' +
        '<button class="btn sm act-plus" title="增加副本">＋</button>' +
        '<button class="btn danger sm act-del">删除</button>' +
      '</div></td>';
    $('.act-start', tr).onclick = () => startFromPoolModal(p.name);
    $('.act-detail', tr).onclick = () => poolDetailModal(p);
    $('.act-minus', tr).onclick = () => scalePool(p.name, p.replicas - 1);
    $('.act-plus', tr).onclick = () => scalePool(p.name, p.replicas + 1);
    $('.act-del', tr).onclick = () => confirmModal('确认删除预热池 <b>' + esc(p.name) + '</b>？其沙箱将被回收。', () => deletePool(p.name));
    rows.appendChild(tr);
  }
}

function renderSandboxes() {
  $('#sbxCount').textContent = state.sandboxes.length;
  $('#navSbxCount').textContent = state.sandboxes.length;
  const rows = $('#sbxRows');
  rows.innerHTML = '';
  const empty = $('#sbxEmpty');
  empty.hidden = state.sandboxes.length > 0;
  for (const s of state.sandboxes) {
    const tr = document.createElement('tr');
    const mountCount = s.mounts ? s.mounts.length : 0;
    tr.innerHTML =
      '<td><div class="mono truncate" style="max-width:150px" title="' + esc(s.sandboxID) + '">' + esc(s.sandboxID) + '</div>' +
        '<div class="muted">' + (s.paused ? '⏸ 暂停' : (s.owner ? esc(s.owner) : '&nbsp;')) + '</div></td>' +
      '<td class="mono">' + esc(s.pool || '-') + '</td>' +
      '<td>' + chip(s.state) + '</td>' +
      '<td class="mono truncate" title="' + esc(s.image) + '">' + esc(s.image || '-') + '</td>' +
      '<td class="mono">' + esc(s.podIP || '-') + '</td>' +
      '<td>' + mountCount + (s.activeMounts && s.activeMounts.length ? ' <span style="color:var(--green)" title="动态挂载数">+' + s.activeMounts.length + '</span>' : '') + '</td>' +
      '<td><div class="actions">' +
        '<button class="btn sm act-detail">详情</button>' +
        mountActionButtons(s) +
        '<button class="btn sm act-inplace">换镜像</button>' +
        (s.paused
          ? '<button class="btn sm act-toggle">恢复</button>'
          : '<button class="btn sm act-toggle">暂停</button>') +
        '<button class="btn danger sm act-del">删除</button>' +
      '</div></td>';
    $('.act-detail', tr).onclick = () => detailModal(s);
    const bMount = $('.act-mount', tr);
    if (bMount) bMount.onclick = () => addMountsModal(s);
    const bDyn = $('.act-dyn', tr);
    if (bDyn) bDyn.onclick = () => dynamicMountModal(s);
    $('.act-inplace', tr).onclick = () => inplaceModal(s);
    $('.act-toggle', tr).onclick = () => (s.paused ? resumeSandbox(s) : pauseSandbox(s));
    $('.act-del', tr).onclick = () => confirmModal(
      (s.pool && !s.claimed)
        ? '该沙箱 <b>' + esc(s.sandboxID) + '</b> 是预热池 <b>' + esc(s.pool) + '</b> 的空闲成员。确认回收它？（相当于把预热池缩容 1）'
        : '确认删除沙箱 <b>' + esc(s.sandboxID) + '</b>？其 Pod 将一并被删除。',
      () => deleteSandbox(s));
    rows.appendChild(tr);
  }
}

// ---------------------------------------------------------------------------
// 刷新 / 健康检查
// ---------------------------------------------------------------------------
async function refresh() {
  $('#autoState').textContent = '同步中…';
  try {
    const [pools, sandboxes] = await Promise.all([api('/pools'), api('/sandboxes')]);
    state.pools = pools || [];
    state.sandboxes = sandboxes || [];
    $('#lastSync').textContent = '上次同步: ' + new Date().toLocaleTimeString();
    setBackend(true);
    renderPools();
    renderSandboxes();
    $('#autoState').textContent = '已同步';
  } catch (e) {
    setBackend(false);
    $('#autoState').textContent = '同步失败';
    $('#lastSync').textContent = e.message;
  }
}

async function checkHealth() {
  try {
    const res = await fetch('/health');
    if (res.ok) { setBackend(true); $('#nsBadge').textContent = 'API 正常'; }
  } catch (e) { setBackend(false); }
}

function setBackend(ok) {
  const b = $('#backendBadge');
  b.textContent = ok ? '后端: 正常' : '后端: 离线';
  b.className = 'badge ' + (ok ? 'ok' : 'err');
}

$('#refreshBtn').onclick = refresh;

function scheduleLoop() {
  if (state.timer) clearInterval(state.timer);
  state.timer = setInterval(() => { checkHealth(); refresh(); }, 5000);
}

// ---------------------------------------------------------------------------
// Pool / Sandbox 动作
// ---------------------------------------------------------------------------
async function scalePool(name, replicas) {
  if (replicas < 0) return;
  try {
    await api('/pools/' + encodeURIComponent(name) + '/scale', { method: 'POST', body: { replicas } });
    toast('已调整 ' + name + ' → ' + replicas + ' 副本', 'ok');
    refresh();
  } catch (e) { toast(e.message, 'err'); }
}

async function deletePool(name) {
  try {
    await api('/pools/' + encodeURIComponent(name), { method: 'DELETE' });
    toast('已删除预热池 ' + name, 'ok');
    refresh();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteSandbox(s) {
  // 空闲预热成员：直接删除单个 CR 会让预热池永久低于期望副本（SandboxSet 只在自身事件时
  // 才 reconcile，不会感知子 Sandbox 被删）。把删除解释为“池缩容 1”，由池控制器回收成员，
  // 保证 期望/可用 始终一致。
  if (s.pool && !s.claimed) {
    const pool = state.pools.find(p => p.name === s.pool);
    const next = Math.max(0, (pool ? pool.replicas : 1) - 1);
    try {
      await api('/pools/' + encodeURIComponent(s.pool) + '/scale', { method: 'POST', body: { replicas: next } });
      toast('已回收预热沙箱，预热池 ' + s.pool + ' 缩容至 ' + next, 'ok');
      refresh();
    } catch (e) { toast(e.message, 'err'); }
    return;
  }
  try {
    await api('/sandboxes/' + encodeURIComponent(s.sandboxID), { method: 'DELETE' });
    toast('已删除沙箱 ' + s.sandboxID, 'ok');
    refresh();
  } catch (e) { toast(e.message, 'err'); }
}

async function pauseSandbox(s) {
  try {
    await api('/sandboxes/' + encodeURIComponent(s.sandboxID) + '/pause', { method: 'POST', body: {} });
    toast('已暂停 ' + s.sandboxID, 'ok');
    refresh();
  } catch (e) { toast(e.message, 'err'); }
}

async function resumeSandbox(s) {
  try {
    await api('/sandboxes/' + encodeURIComponent(s.sandboxID) + '/resume', { method: 'POST', body: {} });
    toast('已恢复 ' + s.sandboxID, 'ok');
    refresh();
  } catch (e) { toast(e.message, 'err'); }
}

// ---------------------------------------------------------------------------
// 挂载/环境变量 编辑器
// ---------------------------------------------------------------------------
function buildMountRow(initial) {
  const init = Object.assign({ type: 'emptyDir', readOnly: false }, initial || {});
  const card = document.createElement('div');
  card.className = 'mount-row';
  card.style.cssText = 'flex-direction:column;align-items:stretch;border:1px solid var(--line);border-radius:8px;padding:8px;';

  const line1 = document.createElement('div');
  line1.style.cssText = 'display:flex;gap:8px;align-items:center;';
  const nameIn = textInput('名称（如 cfg-data）', init.name);
  const pathIn = textInput('挂载路径（如 /workspace/config）', init.mountPath);
  const typeSel = document.createElement('select');
  ['emptyDir', 'configMap', 'pvc', 'hostPath'].forEach(t => {
    const o = document.createElement('option');
    o.value = t; o.textContent = t;
    if (t === init.type) o.selected = true;
    typeSel.appendChild(o);
  });
  const ro = document.createElement('input');
  ro.type = 'checkbox'; ro.checked = !!init.readOnly;
  ro.style.cssText = 'width:auto;';
  const roLabel = document.createElement('span');
  roLabel.className = 'muted'; roLabel.textContent = '只读';
  const del = document.createElement('button');
  del.className = 'btn mini danger'; del.textContent = '✕';
  line1.append(nameIn, pathIn, typeSel, ro, roLabel, del);
  card.appendChild(line1);

  const extraWrap = document.createElement('div');
  card.appendChild(extraWrap);

  // 额外参数区（按类型）
  function renderExtra() {
    extraWrap.innerHTML = '';
    const type = typeSel.value;
    if (type === 'configMap') {
      const cm = textInput('ConfigMap 名称', init.configMap);
      extraWrap.appendChild(field('ConfigMap（留空则按上方名称自动创建）', cm));
      const data = document.createElement('textarea');
      data.rows = 2;
      data.placeholder = '可选：数据内容，每行 KEY=VALUE（自动创建该 ConfigMap）';
      if (init.data) data.value = Object.entries(init.data).map(([k, v]) => k + '=' + v).join('\n');
      extraWrap.appendChild(field('数据（自动创建时）', data));
      extraWrap._cm = cm; extraWrap._data = data;
    } else if (type === 'pvc') {
      const pvc = textInput('PVC 名称', init.pvcName);
      extraWrap.appendChild(field('PersistentVolumeClaim 名称', pvc));
      extraWrap._pvc = pvc;
    } else if (type === 'hostPath') {
      const hp = textInput('宿主机路径', init.hostPath);
      extraWrap.appendChild(field('宿主机路径', hp));
      extraWrap._hp = hp;
    } else if (type === 'emptyDir') {
      const h = document.createElement('div');
      h.className = 'hint'; h.textContent = 'emptyDir：Pod 生命周期内的临时目录，无需外部资源。';
      extraWrap.appendChild(h);
    }
  }
  typeSel.onchange = renderExtra;
  renderExtra();
  del.onclick = () => card.remove();

  function toSpec() {
    const type = typeSel.value;
    const spec = { name: nameIn.value.trim(), mountPath: pathIn.value.trim(), type, readOnly: ro.checked };
    if (!spec.name || !spec.mountPath) throw new Error('挂载需要填写名称与挂载路径');
    if (type === 'configMap') {
      const cmName = (extraWrap._cm ? extraWrap._cm.value : '') || init.configMap || '';
      spec.configMap = cmName.trim() || nameIn.value.trim();
      const raw = extraWrap._data ? extraWrap._data.value : '';
      const data = {};
      raw.split('\n').forEach(line => {
        const i = line.indexOf('=');
        if (i > 0) data[line.slice(0, i).trim()] = line.slice(i + 1);
      });
      if (Object.keys(data).length) spec.data = data;
    } else if (type === 'pvc') spec.pvcName = (extraWrap._pvc ? extraWrap._pvc.value : '').trim();
    else if (type === 'hostPath') spec.hostPath = (extraWrap._hp ? extraWrap._hp.value : '').trim();
    return spec;
  }
  card._toSpec = toSpec;
  return card;
}

function addMountToList(listEl) {
  const row = buildMountRow({});
  listEl.appendChild(row);
  $('input', row).focus();
}

function buildEnvRow(initial) {
  const init = initial || { k: '', v: '' };
  const row = document.createElement('div');
  row.className = 'env-row';
  const kIn = textInput('KEY', init.k);
  const vIn = textInput('VALUE', init.v);
  const del = document.createElement('button');
  del.className = 'btn mini danger'; del.textContent = '✕';
  del.onclick = () => row.remove();
  row.append(kIn, vIn, del);
  row._k = kIn; row._v = vIn;
  return row;
}

function addEnvToList(listEl) {
  const row = buildEnvRow({});
  listEl.appendChild(row);
  $('input', row).focus();
}

// 默认给出一个示例 mount（configMap 挂载演示）
function defaultMountSamples(listEl) {
  const r = buildMountRow({ name: 'app-config', mountPath: '/etc/app-config', type: 'configMap', data: { 'app.properties': 'mode=demo\nhello=acx' } });
  listEl.appendChild(r);
}

function mountsEditor(existing) {
  const wrap = document.createElement('div');
  const list = document.createElement('div');
  list.className = 'mount-list';
  wrap.appendChild(list);
  const add = document.createElement('button');
  add.className = 'btn sm';
  add.textContent = '＋ 添加挂载';
  add.onclick = () => { const r = buildMountRow({}); list.appendChild(r); $('input', r).focus(); };
  wrap.appendChild(add);
  if (existing && existing.length) {
    existing.forEach(m => list.appendChild(buildMountRow(m)));
  } else {
    defaultMountSamples(list);
  }
  wrap._list = list;
  return wrap;
}

function envsEditor(existing) {
  const wrap = document.createElement('div');
  const list = document.createElement('div');
  list.className = 'env-list';
  wrap.appendChild(list);
  const add = document.createElement('button');
  add.className = 'btn sm';
  add.textContent = '＋ 添加环境变量';
  add.onclick = () => { const r = buildEnvRow({}); list.appendChild(r); $('input', r).focus(); };
  wrap.appendChild(add);
  if (existing && existing.length) existing.forEach(e => list.appendChild(buildEnvRow(e)));
  wrap._list = list;
  return wrap;
}

// ---------------------------------------------------------------------------
// 弹窗：新建预热池
// ---------------------------------------------------------------------------
function createPoolModal() {
  const box = document.createElement('div');
  const nameIn = textInput('pool-demo（可留空自动生成）');
  const imageIn = textInput('如 image.ac.com:5000/acx/sandbox-base:latest');
  const replicasIn = numInput('副本数', 1, 1);
  box.appendChild(field('预热池名称', nameIn));
  box.appendChild(field('基础镜像 *', imageIn));
  box.appendChild(field('预热副本数', replicasIn));

  const ar = document.createElement('label');
  ar.style.cssText = 'display:flex;align-items:flex-start;gap:8px;margin:0 0 12px;font-size:13px;color:var(--text);cursor:pointer;line-height:1.6;';
  const arChk = document.createElement('input');
  arChk.type = 'checkbox';
  arChk.checked = true;
  arChk.style.cssText = 'width:auto;margin:2px 0 0;';
  ar.appendChild(arChk);
  ar.appendChild(document.createTextNode('启用 agent-runtime（不重建 Pod 的 CSI 动态挂载：Sidecar 注入 + 共享 /mnt/envd + mountPropagation）'));
  box.appendChild(ar);

  // hostMounts：把 worker 节点上"已挂载"的宿主目录（如现有 NFS /gridview-niesl113-10033113）
  // 以 hostPath 卷注入 agent-runtime Sidecar，供 bind 动态挂载使用；仅 Sidecar 可见，应用容器看不到。
  const hmWrap = document.createElement('div');
  const hmIn = textInput('如 /gridview-niesl113-10033113（多个用逗号分隔）');
  hmWrap.appendChild(field('节点宿主目录 hostMounts（可选，复用节点现有挂载）', hmIn,
    '以同一绝对路径 hostPath 挂进 agent-runtime Sidecar。随后动态挂载的 PV 若为 acx.csi.bind（volumeAttributes.path 指向其中某目录）即可 mount --bind 进应用容器。'));
  const hmDisable = () => { hmWrap.style.display = arChk.checked ? '' : 'none'; hmIn.disabled = !arChk.checked; };
  arChk.addEventListener('change', hmDisable);
  box.appendChild(hmWrap);
  hmDisable();

  // dynamicRoots：把共享的动态挂载树以 HostToContainer 挂到业务容器的自定义目录（如 /data）。
  // 配置后动态挂载只出现在 <root>/volumes/<mountId>，不再挂默认 /mnt/envd，避免同一挂载重复可见；
  // 全程仍不重建 Pod。
  const drWrap = document.createElement('div');
  const drIn = textInput('如 /data（多个用逗号分隔；应用容器内可见动态挂载的根目录）');
  drWrap.appendChild(field('业务容器内挂载根 dynamicRoots（可选）', drIn,
    '配置后业务容器不再挂默认 <span class="mono">/mnt/envd</span>，共享卷只挂到这些根目录；动态挂载唯一出现在 <span class="mono">&lt;root&gt;/volumes/&lt;mountId&gt;</span>（示例：/data/volumes/demo-abc），不再重复。留空则维持默认 /mnt/envd 路径。'));
  const drDisable = () => { drWrap.style.display = arChk.checked ? '' : 'none'; drIn.disabled = !arChk.checked; };
  arChk.addEventListener('change', drDisable);
  box.appendChild(drWrap);
  drDisable();

  const msec = document.createElement('div');
  msec.className = 'subsection';
  msec.textContent = '镜像内常用挂载（可跳过，启动应用时再挂）';
  box.appendChild(msec);
  const mounts = mountsEditor([]);
  box.appendChild(mounts);

  const modal = openModal('新建预热池', box);
  modal.box.appendChild(modalFoot([
    { label: '取消', onClick: closeModal },
    {
      label: '创建', cls: 'primary', onClick: async (btn) => {
        buttonBusy(btn, true);
        try {
          const body = {
            name: nameIn.value.trim() || undefined,
            image: imageIn.value.trim(),
            replicas: parseInt(replicasIn.value, 10) || 1,
            mounts: $$('.mount-row', mounts).map(el => el._toSpec()),
          };
          if (arChk.checked) body.runtimes = [{ name: 'agent-runtime' }];
          const hmRaw = hmIn.value.trim();
          if (arChk.checked && hmRaw) {
            body.hostMounts = hmRaw.split(',').map(s => s.trim()).filter(Boolean);
          }
          const drRaw = drIn.value.trim();
          if (arChk.checked && drRaw) {
            body.dynamicRoots = drRaw.split(',').map(s => s.trim()).filter(Boolean);
          }
          await api('/pools', { method: 'POST', body });
          toast('预热池创建成功，开始预热沙箱…', 'ok');
          closeModal();
          refresh();
        } catch (e) { toast(e.message, 'err'); buttonBusy(btn, false); }
      }
    }
  ]));
}

// ---------------------------------------------------------------------------
// 弹窗：从预热池启动应用
// ---------------------------------------------------------------------------
function startFromPoolModal(poolName) {
  const pool = state.pools.find(p => p.name === poolName);
  const box = document.createElement('div');
  const hint = document.createElement('div');
  hint.className = 'hint';
  hint.innerHTML = '从预热池 <b>' + esc(poolName) + '</b> 领取一个已就绪沙箱（可用 ' + (pool ? pool.available : '?') + ' 个）。领取后即脱离预热池成为独立应用 Pod。';
  box.appendChild(hint);

  const imageIn = textInput('留空 = 沿用池镜像 ' + (pool && pool.image ? '(' + pool.image + ')' : ''));
  imageIn.placeholder = '覆盖镜像（可选）';
  box.appendChild(field('应用镜像（可选，不填则使用预热池镜像）', imageIn));

  const cmdIn = document.createElement('textarea');
  cmdIn.rows = 2;
  cmdIn.placeholder = '启动命令（可选），如：python -m http.server 8000 --directory /workspace';
  box.appendChild(field('启动命令（可选）', cmdIn));

  const msec = document.createElement('div');
  msec.className = 'subsection'; msec.textContent = '动态挂载（自定义）';
  box.appendChild(msec);
  const mounts = mountsEditor([]);
  box.appendChild(mounts);

  const esec = document.createElement('div');
  esec.className = 'subsection'; esec.textContent = '环境变量';
  box.appendChild(esec);
  const envs = envsEditor([{ k: 'APP_NAME', v: poolName }]);
  box.appendChild(envs);

  const modal = openModal('从预热池启动应用：' + poolName, box);
  modal.box.appendChild(modalFoot([
    { label: '取消', onClick: closeModal },
    {
      label: '快速启动', cls: 'primary', onClick: async (btn) => {
        buttonBusy(btn, true);
        try {
          const body = { image: imageIn.value.trim() || undefined, command: cmdIn.value.trim() || undefined };
          const ms = $$('.mount-row', mounts).map(el => el._toSpec());
          if (ms.length) body.mounts = ms;
          const env = {};
          $$('.env-row', envs).forEach(row => {
            const k = row._k.value.trim();
            if (k) env[k] = row._v.value;
          });
          if (Object.keys(env).length) body.env = env;
          const sbx = await api('/pools/' + encodeURIComponent(poolName) + '/start', { method: 'POST', body });
          closeModal();
          toast('已领取沙箱 ' + sbx.sandboxID + '，应用 Pod 启动中…', 'ok');
          refresh();
          showView('sandboxes');
          // 若启动需重建 Pod，则异步跟踪状态
          if (sbx.adjust === 'rebuild' || sbx.adjust === 'inplace') {
            setTimeout(openDetailAfter, 600, sbx.sandboxID);
          } else {
            setTimeout(openDetailAfter, 900, sbx.sandboxID);
          }
        } catch (e) { toast(e.message, 'err'); buttonBusy(btn, false); }
      }
    }
  ]));
}

function openDetailAfter(id) {
  const s = state.sandboxes.find(x => x.sandboxID === id);
  if (s) detailModal(s);
}

// ---------------------------------------------------------------------------
// 弹窗：追加挂载
// ---------------------------------------------------------------------------
function addMountsModal(sandbox) {
  const box = document.createElement('div');
  const hint = document.createElement('div');
  hint.className = 'hint';
  hint.innerHTML = '为 <b>' + esc(sandbox.sandboxID) + '</b> 追加挂载。Kubernetes 不允许在运行中直接改挂载，系统将<b>原地重建 Pod</b>（沙箱 ID/IP 会变化）使新挂载生效。';
  box.appendChild(hint);
  const mounts = mountsEditor([]);
  box.appendChild(mounts);

  const modal = openModal('为沙箱追加挂载：' + sandbox.sandboxID, box);
  modal.box.appendChild(modalFoot([
    { label: '取消', onClick: closeModal },
    {
      label: '提交并重建', cls: 'primary', onClick: async (btn) => {
        buttonBusy(btn, true);
        try {
          const ms = $$('.mount-row', mounts).map(el => el._toSpec());
          if (!ms.length) throw new Error('请至少添加一个挂载');
          const sbx = await api('/sandboxes/' + encodeURIComponent(sandbox.sandboxID) + '/mounts', { method: 'POST', body: { mounts: ms } });
          closeModal();
          toast('已提交挂载，Pod 重建中…', 'ok');
          refresh();
          setTimeout(openDetailAfter, 900, sbx.sandboxID);
        } catch (e) { toast(e.message, 'err'); buttonBusy(btn, false); }
      }
    }
  ]));
}

// ---------------------------------------------------------------------------
// 弹窗：In-Place 换镜像
// ---------------------------------------------------------------------------
function inplaceModal(sandbox) {
  const box = document.createElement('div');
  const hint = document.createElement('div');
  hint.className = 'hint';
  hint.innerHTML = '对 <b>' + esc(sandbox.sandboxID) + '</b> 执行原地（In-Place）镜像调整。Pod <b>名称 / UID / IP 保持不变</b>，由 kubelet 用新镜像重启容器。<br>当前镜像：<span class="mono">' + esc(sandbox.image || '-') + '</span>';
  box.appendChild(hint);
  const imageIn = textInput('如 image.ac.com:5000/acx/app-v2:latest');
  box.appendChild(field('新镜像 *', imageIn));

  const modal = openModal('原地调整镜像：' + sandbox.sandboxID, box);
  modal.box.appendChild(modalFoot([
    { label: '取消', onClick: closeModal },
    {
      label: '原地调整', cls: 'primary', onClick: async (btn) => {
        const image = imageIn.value.trim();
        if (!image) { toast('请输入新镜像', 'err'); return; }
        buttonBusy(btn, true);
        try {
          const sbx = await api('/sandboxes/' + encodeURIComponent(sandbox.sandboxID) + '/inplace', { method: 'POST', body: { image } });
          closeModal();
          toast('已提交原地换镜像，容器重启中（IP 不变）…', 'ok');
          refresh();
          setTimeout(openDetailAfter, 1500, sbx.sandboxID);
        } catch (e) { toast(e.message, 'err'); buttonBusy(btn, false); }
      }
    }
  ]));
}

// ---------------------------------------------------------------------------
// 弹窗：动态挂载（不重建 Pod）
// ---------------------------------------------------------------------------
function dynamicMountModal(sandbox) {
  const box = document.createElement('div');
  const hint = document.createElement('div');
  hint.className = 'hint';
  const hasRoots = sandbox.dynamicRoots && sandbox.dynamicRoots.length;
  const pathLine = hasRoots
    ? '本沙箱配置了业务容器内可见根 <span class="mono">' + sandbox.dynamicRoots.map(esc).join('</span>、<span class="mono">') + '</span>，业务容器不再挂默认 /mnt/envd。<br>' +
      '挂载后唯一可见于 <span class="mono">&lt;根&gt;/volumes/&lt;mountId&gt;</span>（如 <span class="mono">' + esc(sandbox.dynamicRoots[0]) + '/volumes/&lt;mountId&gt;</span>；可指定 mountId 或自动生成）。'
    : '容器内访问路径为 <span class="mono">/mnt/envd/volumes/&lt;mountId&gt;</span>（可指定 mountId 或自动生成）。';
  hint.innerHTML = '对 <b>' + esc(sandbox.sandboxID) + '</b> 执行 <b>CSI 动态挂载（不重建 Pod）</b>：请求发给 Pod 内 agent-runtime Sidecar，由它在共享目录把卷挂上，业务容器通过 mountPropagation <b>即时可见</b>，Pod UID / IP 保持不变。' +
    '挂载来源可以是 <b>PV</b>、<b>宿主目录</b>（池 hostMounts 已注入的根路径，无需 PV）或 <b>tmpfs 内存盘</b>。<br>' +
    pathLine;
  box.appendChild(hint);

  // ---------- 来源类型选择 ----------
  const srcSel = document.createElement('select');
  srcSel.style.marginBottom = '12px';
  [['pv', 'PersistentVolume（PV）'], ['host', '宿主目录直 bind（池 hostMounts 内，无需 PV）'], ['tmpfs', 'tmpfs 内存盘']].forEach(([v, label]) => {
    const o = document.createElement('option');
    o.value = v; o.textContent = label;
    srcSel.appendChild(o);
  });
  box.appendChild(field('挂载来源 *', srcSel));

  // ---------- PV ----------
  const pvBlock = document.createElement('div');
  const pvIn = textInput('选择或输入 PV，如 demo-local / demo-tmpfs');
  pvIn.setAttribute('list', 'dynPvOptions');
  const pvList = document.createElement('datalist');
  pvList.id = 'dynPvOptions';
  pvBlock.appendChild(pvList);
  pvBlock.appendChild(field('PersistentVolume 名称 *', pvIn));
  // 拉取集群中可用于动态挂载的 CSI PV，作为下拉候选项
  api('/pvs').then(list => {
    if (!Array.isArray(list)) return;
    pvList.innerHTML = '';
    list.forEach(pv => {
      const o = document.createElement('option');
      o.value = pv.name || '';
      o.label = (pv.driver || '') + (pv.path ? ' · ' + pv.path : '');
      pvList.appendChild(o);
    });
  }).catch(() => { /* 忽略：仍可手动输入 */ });
  box.appendChild(pvBlock);

  // ---------- 宿主目录直 bind ----------
  const hostBlock = document.createElement('div');
  const hostPathIn = textInput('宿主绝对路径，如 /data/data1 或 /data/data1/zdk');
  hostPathIn.setAttribute('list', 'dynHostOptions');
  const hostList = document.createElement('datalist');
  hostList.id = 'dynHostOptions';
  hostBlock.appendChild(hostList);
  const roots = (sandbox.hostMounts && sandbox.hostMounts.length) ? sandbox.hostMounts : [];
  roots.forEach(r => { const o = document.createElement('option'); o.value = r; hostList.appendChild(o); });
  hostBlock.appendChild(field('宿主路径 *（bind）', hostPathIn,
    roots.length
      ? '该池已注入的宿主根：<span class="mono">' + roots.map(esc).join('</span>、<span class="mono">') + '</span>。<br>可填任一宿主根或其下任意子目录（不存在会自动创建）。'
      : '<b style="color:var(--red)">该沙箱未注入任何宿主根（池 hostMounts 为空），host 来源不可用</b>；请在建池时填写 hostMounts 并重建预热成员。'));
  box.appendChild(hostBlock);

  // ---------- tmpfs ----------
  const tmpBlock = document.createElement('div');
  const sizeIn = numInput('留空 = 内核默认（约内存一半）', null, 1);
  sizeIn.placeholder = '留空 = 内核默认';
  tmpBlock.appendChild(field('容量 sizeMi（可选，如 256）', sizeIn));
  box.appendChild(tmpBlock);

  // ---------- 公共：subPath / mountId / readOnly ----------
  const subFieldWrap = field('子路径 subPath（可选，pv/host 可用）', textInput('留空 = 根；填如 zdk，则挂载 <root>/zdk'), 'host 示例：宿主根 <span class="mono">/data/data1</span> + subPath <span class="mono">zdk</span> → 挂载 <span class="mono">/data/data1/zdk</span>');
  const subIn = $('input', subFieldWrap);
  box.appendChild(subFieldWrap);

  const idIn = textInput('留空 = 自动生成');
  box.appendChild(field('mountId（可选）', idIn));

  const ro = document.createElement('label');
  ro.style.cssText = 'display:flex;align-items:flex-start;gap:8px;margin:0 0 12px;font-size:13px;color:var(--text);cursor:pointer;';
  const roChk = document.createElement('input');
  roChk.type = 'checkbox';
  roChk.style.cssText = 'width:auto;margin:2px 0 0;';
  ro.appendChild(roChk);
  ro.appendChild(document.createTextNode('只读挂载（readOnly，bind 会 remount,ro；tmpfs 不支持）'));
  box.appendChild(ro);

  // 来源切换时联动显隐
  function applySource() {
    const v = srcSel.value;
    pvBlock.style.display = v === 'pv' ? '' : 'none';
    hostBlock.style.display = v === 'host' ? '' : 'none';
    tmpBlock.style.display = v === 'tmpfs' ? '' : 'none';
    subFieldWrap.style.display = v === 'tmpfs' ? 'none' : '';
    ro.style.display = v === 'tmpfs' ? 'none' : '';
  }
  srcSel.onchange = applySource;
  applySource();

  const modal = openModal('动态挂载：' + sandbox.sandboxID, box);
  modal.box.appendChild(modalFoot([
    { label: '取消', onClick: closeModal },
    {
      label: '动态挂载', cls: 'primary', onClick: async (btn) => {
        const src = srcSel.value;
        const mountId = idIn.value.trim();
        if (mountId && !/^[a-z0-9][a-z0-9._-]{0,62}$/.test(mountId)) {
          toast('mountId 仅允许 字母/数字 开头，可含 . _ -', 'err'); return;
        }
        const body = {};
        if (src === 'pv') {
          const pvName = pvIn.value.trim();
          if (!pvName) { toast('请输入 PersistentVolume 名称', 'err'); return; }
          body.pvName = pvName;
        } else if (src === 'host') {
          const path = hostPathIn.value.trim();
          if (!path.startsWith('/')) { toast('宿主路径必须是 / 开头的绝对路径', 'err'); return; }
          body.source = 'host'; body.path = path;
        } else {
          body.source = 'tmpfs';
          const size = sizeIn.value.trim();
          if (size) {
            if (!/^\d+$/.test(size)) { toast('sizeMi 必须是整数', 'err'); return; }
            body.sizeMi = size;
          }
        }
        const sub = subIn.value.trim();
        if (sub && src !== 'tmpfs') body.subPath = sub;
        if (src !== 'tmpfs' && roChk.checked) body.readOnly = true;
        if (mountId) body.mountId = mountId;
        buttonBusy(btn, true);
        try {
          const sbx = await api('/sandboxes/' + encodeURIComponent(sandbox.sandboxID) + '/dynamic-mounts', { method: 'POST', body });
          toast('动态挂载成功（Pod 未重建）', 'ok');
          refresh();
          detailModal(sbx);
        } catch (e) { toast(e.message, 'err'); buttonBusy(btn, false); }
      }
    }
  ]));
}

async function dynamicUmount(sandbox, mountId) {
  try {
    const sbx = await api('/sandboxes/' + encodeURIComponent(sandbox.sandboxID) + '/dynamic-umount', { method: 'POST', body: { mountId } });
    toast('已卸载动态挂载：' + mountId, 'ok');
    refresh();
    detailModal(sbx);
  } catch (e) {
    toast(e.message, 'err');
    detailModal(sandbox);
  }
}

// ---------------------------------------------------------------------------
// 弹窗：详情
// ---------------------------------------------------------------------------
function detailModal(s) {
  const box = document.createElement('div');
  const dl = document.createElement('dl');
  dl.className = 'detail-list';
  const fields = [
    ['沙箱 ID', s.sandboxID],
    ['名称', s.name],
    ['命名空间', s.namespace],
    ['预热池', s.pool || '-'],
    ['状态', s.state + (s.reason ? ' (' + s.reason + ')' : '')],
    ['阶段', s.phase || '-'],
    ['镜像', s.image || '-'],
    ['Pod IP', s.podIP || '-'],
    ['节点', s.nodeName || '-'],
    ['所属人', s.owner || '-'],
    ['暂停', s.paused ? '是' : '否'],
    ['最近调整', s.adjust || '-'],
    ['创建时间', s.createdAt || '-'],
    ['关机时间', s.shutdownTime || '-'],
  ];
  if (s.dynamicRoots && s.dynamicRoots.length) {
    fields.push(['动态可见根', s.dynamicRoots.join(', ')]);
  }
  for (const [k, v] of fields) {
    const dt = document.createElement('dt'); dt.textContent = k;
    const dd = document.createElement('dd'); dd.innerHTML = esc(v);
    dl.append(dt, dd);
  }
  box.appendChild(dl);

  const msec = document.createElement('div');
  msec.className = 'subsection'; msec.textContent = '当前挂载 (' + (s.mounts ? s.mounts.length : 0) + ')';
  box.appendChild(msec);
  const ml = document.createElement('ul');
  ml.style.cssText = 'margin:0;padding-left:18px;color:var(--text);font-family:SF Mono,Menlo,monospace;font-size:12px;line-height:1.9;';
  if (s.mounts && s.mounts.length) s.mounts.forEach(p => { const li = document.createElement('li'); li.textContent = p; ml.appendChild(li); });
  else { const li = document.createElement('li'); li.className = 'muted'; li.textContent = '（无自定义挂载）'; ml.appendChild(li); }
  box.appendChild(ml);

  const dmsec = document.createElement('div');
  dmsec.className = 'subsection';
  dmsec.textContent = '动态挂载（agent-runtime）(' + (s.activeMounts ? s.activeMounts.length : 0) + ')'
    + (s.agentRuntime ? '' : ' — 池未启用 agent-runtime');
  box.appendChild(dmsec);
  const dml = document.createElement('ul');
  dml.style.cssText = 'margin:0;padding-left:0;list-style:none;font-size:12px;';
  if (s.activeMounts && s.activeMounts.length) {
    s.activeMounts.forEach(m => {
      const li = document.createElement('li');
      li.style.cssText = 'display:flex;justify-content:space-between;gap:10px;align-items:flex-start;padding:6px 0;border-bottom:1px solid var(--line);';
      const info = document.createElement('div');
      info.style.cssText = 'line-height:1.8;';
      const origin = m.pvName
        ? 'PV <span class="mono">' + esc(m.pvName) + '</span>'
        : (m.source ? '来源 <span class="mono">' + esc(m.source) + '</span>' : '<span class="mono">' + esc(m.driver || '-') + '</span>');
      const cp = m.containerPath || '';
      // containerPath is the canonical shared-tree target (/mnt/envd/volumes/<mountId>). With custom
      // dynamicRoots the app container no longer mounts /mnt/envd, so show the roots-relative paths
      // that are actually visible inside the app; without roots show the canonical path directly.
      const suffix = cp.indexOf('/mnt/envd') === 0 ? cp.slice('/mnt/envd'.length) : '';
      const roots = (s.dynamicRoots && s.dynamicRoots.length && suffix) ? s.dynamicRoots : [];
      const visibleLine = roots.length
        ? '容器内 <span class="mono">' + esc(roots[0] + suffix) + '</span>' +
          (roots.length > 1 ? '<br>另见 ' + roots.slice(1).map(r => '<span class="mono">' + esc(r + suffix) + '</span>').join('、') : '') + '<br>'
        : '容器内 <span class="mono">' + esc(cp || '-') + '</span><br>';
      info.innerHTML =
        '<b class="mono">' + esc(m.mountId) + '</b> <span class="muted">' + esc(m.driver)
          + (m.readOnly ? ' · ro' : ' · rw') + '</span><br>' +
        origin
          + (m.subPath ? ' · subPath <span class="mono">' + esc(m.subPath) + '</span>' : '') + '<br>' +
        visibleLine +
        '<span class="muted">Pod ' + esc(m.podUid ? String(m.podUid).slice(0, 8) : '-')
          + ' · ' + esc(m.mountedAt || '') + '</span>';
      const btn = document.createElement('button');
      btn.className = 'btn sm danger';
      btn.textContent = '卸载';
      btn.style.cssText = 'flex-shrink:0;';
      btn.onclick = () => confirmModal('确认卸载动态挂载 <b class="mono">' + esc(m.mountId) + '</b>？<br>仅移除该挂载，Pod 保持运行。', () => dynamicUmount(s, m.mountId));
      li.append(info, btn);
      dml.appendChild(li);
    });
  } else {
    const li = document.createElement('li');
    li.className = 'muted';
    li.textContent = '（无动态挂载）';
    dml.appendChild(li);
  }
  box.appendChild(dml);

  const footBtns = [{ label: '关闭', cls: 'primary', onClick: closeModal }];
  if (canDynMount(s)) {
    footBtns.unshift({
      label: '＋ 动态挂载', onClick: () => { closeModal(); dynamicMountModal(s); }
    });
  }
  const modal = openModal('沙箱详情', box);
  modal.box.appendChild(modalFoot(footBtns));
}

// ---------------------------------------------------------------------------
// 弹窗：预热池详情（池内空闲沙箱 + 快捷动态挂载）
// ---------------------------------------------------------------------------
function poolDetailModal(p) {
  const members = state.sandboxes.filter(x => x.pool === p.name);
  const box = document.createElement('div');

  const dl = document.createElement('dl');
  dl.className = 'detail-list';
  const fields = [
    ['预热池', p.name],
    ['命名空间', p.namespace || '-'],
    ['镜像', p.image || '-'],
    ['期望副本', String(p.replicas)],
    ['可用 / 创建中', p.available + ' / ' + p.creating],
    ['已启动（领取）', String(p.claimed)],
    ['动态挂载能力', p.agentRuntime ? '已启用 agent-runtime' : '未启用'],
  ];
  for (const [k, v] of fields) {
    const dt = document.createElement('dt'); dt.textContent = k;
    const dd = document.createElement('dd'); dd.innerHTML = esc(v);
    dl.append(dt, dd);
  }
  box.appendChild(dl);

  if (p.hostMounts && p.hostMounts.length) {
    const hsec = document.createElement('div');
    hsec.className = 'subsection';
    hsec.textContent = '节点宿主目录 hostMounts（bind 动态挂载源，仅 Sidecar 可见）';
    box.appendChild(hsec);
    const ul = document.createElement('ul');
    ul.style.cssText = 'margin:0;padding-left:18px;color:var(--text);font-family:SF Mono,Menlo,monospace;font-size:12px;line-height:1.9;';
    p.hostMounts.forEach(h => { const li = document.createElement('li'); li.textContent = h; ul.appendChild(li); });
    box.appendChild(ul);
  }

  if (p.dynamicRoots && p.dynamicRoots.length) {
    const rsec = document.createElement('div');
    rsec.className = 'subsection';
    rsec.textContent = '业务容器内动态挂载可见根 dynamicRoots（配置后动态挂载只出现在 <root>/volumes/<mountId>，不再挂默认 /mnt/envd）';
    box.appendChild(rsec);
    const ul = document.createElement('ul');
    ul.style.cssText = 'margin:0;padding-left:18px;color:var(--text);font-family:SF Mono,Menlo,monospace;font-size:12px;line-height:1.9;';
    p.dynamicRoots.forEach(r => { const li = document.createElement('li'); li.textContent = r; ul.appendChild(li); });
    box.appendChild(ul);
  }

  const msec = document.createElement('div');
  msec.className = 'subsection';
  msec.textContent = '池内空闲沙箱 (' + members.length + ')';
  box.appendChild(msec);

  if (!members.length) {
    const h = document.createElement('div');
    h.className = 'hint';
    h.innerHTML = '当前没有空闲沙箱。可用预热池行的「＋/－」调整副本，等待预热完成后再来查看。';
    box.appendChild(h);
  } else {
    const list = document.createElement('div');
    list.style.cssText = 'display:flex;flex-direction:column;gap:8px;';
    for (const m of members) {
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;align-items:center;gap:8px;border:1px solid var(--line);border-radius:8px;padding:7px 10px;';
      const id = document.createElement('div');
      id.style.cssText = 'flex:1.4;min-width:0;';
      id.innerHTML =
        '<div class="mono truncate" style="max-width:220px" title="' + esc(m.sandboxID) + '">' + esc(m.sandboxID) + '</div>' +
        '<div class="muted">' + esc(m.podIP || '-') + (m.nodeName ? ' · ' + esc(m.nodeName) : '')
          + (m.mounts && m.mounts.length ? ' · ' + m.mounts.length + ' 挂载' : '') + '</div>';
      row.appendChild(id);
      const st = document.createElement('span');
      st.innerHTML = chip(m.state) + (m.paused ? ' ⏸' : '');
      row.appendChild(st);
      const btns = document.createElement('div');
      btns.style.cssText = 'display:flex;gap:6px;align-items:center;margin-left:auto;flex-shrink:0;';
      const detail = document.createElement('button');
      detail.className = 'btn sm';
      detail.textContent = '详情';
      detail.onclick = () => detailModal(m);
      btns.appendChild(detail);
      if (canDynMount(m)) {
        const dyn = document.createElement('button');
        dyn.className = 'btn sm';
        dyn.textContent = '动态挂载';
        dyn.title = '不重建 Pod，直接向该沙箱挂载 CSI PV';
        dyn.onclick = () => dynamicMountModal(m);
        btns.appendChild(dyn);
      }
      row.appendChild(btns);
      list.appendChild(row);
    }
    box.appendChild(list);
  }

  const modal = openModal('预热池详情：' + p.name, box);
  modal.box.appendChild(modalFoot([
    { label: '关闭', cls: 'primary', onClick: closeModal }
  ]));
}

// ---------------------------------------------------------------------------
// 确认框
// ---------------------------------------------------------------------------
function confirmModal(html, onYes) {
  const box = document.createElement('div');
  const p = document.createElement('p');
  p.innerHTML = html;
  p.style.cssText = 'line-height:1.8;margin:4px 0 8px;';
  box.appendChild(p);
  const modal = openModal('请确认', box);
  modal.box.appendChild(modalFoot([
    { label: '取消', onClick: closeModal },
    { label: '确认', cls: 'danger', onClick: () => { closeModal(); onYes(); } }
  ]));
}

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------
function init() {
  $('#createPoolBtn').onclick = createPoolModal;
  $('#navPools').onclick = () => showView('pools');
  $('#navSandboxes').onclick = () => showView('sandboxes');
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeModal(); });
  showView('pools');
  refresh();
  scheduleLoop();
}

document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', init) : init();
