// 轨迹面板：按轮展示，点击展开该轮的完整执行记录。所有投影字段由后端提供。
const panel = document.getElementById('trace-panel');
const roundsEl = document.getElementById('trace-rounds');
const summaryEl = document.getElementById('trace-summary');

const ROUND_STATUS = { running: '执行中', completed: '已完成', failed: '失败' };
const ROW_STATUS = {
  running: '执行中', pending: '等待执行', completed: '已完成', failed: '失败',
  interrupted: '已中断', unconfirmed: '结果未确认', cancelled: '已取消'
};
const KIND_LABEL = { model: '模型', tool: '工具', system: '事件', user: '用户', assistant: '回复' };

let canvasId = null;
let lastSignature = '';
const expanded = new Map();
const openRounds = new Set();
const seenStatus = new Map();
const loading = new Set();

const pad = (n) => String(n).padStart(2, '0');

const formatTime = (at) => {
  if (!at) return '';
  const date = new Date(at);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
};

const formatDuration = (row) => {
  if (!row.startedAt || !row.endedAt) return '';
  const ms = row.endedAt - row.startedAt;
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m ${Math.floor(ms % 60000 / 1000)}s`;
};

export function setCanvas(id) {
  if (canvasId === id) return;
  canvasId = id;
  lastSignature = '';
  expanded.clear();
  openRounds.clear();
  seenStatus.clear();
  roundsEl.replaceChildren();
  summaryEl.textContent = '';
}

export function renderRounds(list) {
  // 数据未变化时不动 DOM：轮询重建会让展开的输入输出重新渲染并把面板滚回顶部。
  const signature = JSON.stringify(list);
  if (signature === lastSignature) return;
  lastSignature = signature;
  // 面板显隐只由 tab 切换控制，轮询渲染不能把轨迹重新显示到对话页。
  renderSummary(list);
  const scrollTop = panel.scrollTop;
  if (!list.length) {
    roundsEl.replaceChildren(emptyNode());
    panel.scrollTop = scrollTop;
    return;
  }
  const existing = new Map();
  for (const node of roundsEl.children) {
    if (node.dataset.runId) existing.set(node.dataset.runId, node);
  }
  const nodes = [];
  for (const round of list) {
    let details = existing.get(round.runId);
    if (!details) details = createRound(round.runId);
    updateRound(details, round);
    existing.delete(round.runId);
    nodes.push(details);
  }
  // 已消失的轮次移除；顺序未变化时不重排，避免清空滚动容器。
  for (const stale of existing.values()) stale.remove();
  const orderChanged = nodes.length !== roundsEl.children.length
    || nodes.some((node, index) => roundsEl.children[index] !== node);
  if (orderChanged) roundsEl.replaceChildren(...nodes);
  // 打开中的轮次在运行期间持续刷新；结束后补拉一次最终记录。
  for (const round of list) {
    if (!openRounds.has(round.runId)) continue;
    const changed = seenStatus.get(round.runId) !== round.status;
    if (round.status !== 'running' && !changed) continue;
    seenStatus.set(round.runId, round.status);
    const body = findBody(round.runId);
    if (body) void refreshRound(round.runId, body);
  }
  panel.scrollTop = scrollTop;
}

function renderSummary(list) {
  const tools = list.reduce((sum, round) => sum + (Number(round.tools) || 0), 0);
  const running = list.filter((round) => round.status === 'running').length;
  const failed = list.filter((round) => round.status === 'failed').length;
  const parts = [`${list.length} 轮`, `${tools} 次工具调用`];
  if (running) parts.push(`${running} 轮执行中`);
  if (failed) parts.push(`${failed} 轮失败`);
  summaryEl.textContent = parts.join(' · ');
}

function createRound(runId) {
  const details = document.createElement('details');
  details.className = 'trace-round';
  details.dataset.runId = runId;
  details.open = openRounds.has(runId);
  const body = document.createElement('div');
  body.className = 'trace-round-body';
  details.append(body);
  details.addEventListener('toggle', () => {
    if (!details.open) {
      openRounds.delete(runId);
      return;
    }
    openRounds.add(runId);
    // 记录打开时的状态，避免下一次轮询把刚展开的内容再重建一次。
    seenStatus.set(runId, details.dataset.status ?? '');
    const cached = expanded.get(runId);
    if (cached) body.replaceChildren(...rowNodes(cached));
    else body.textContent = '加载中…';
    void refreshRound(runId, body);
  });
  return details;
}

function updateRound(details, round) {
  details.dataset.status = round.status ?? '';
  const summary = document.createElement('summary');
  const label = document.createElement('span');
  label.className = 'trace-round-label';
  label.textContent = `第 ${round.index} 轮`;
  const user = document.createElement('span');
  user.className = 'trace-round-user';
  user.textContent = round.user;
  const state = document.createElement('span');
  state.className = 'trace-pill';
  state.dataset.status = round.status ?? '';
  state.textContent = ROUND_STATUS[round.status] ?? round.status ?? '';
  const meta = document.createElement('span');
  meta.className = 'trace-round-meta';
  const parts = [`${round.steps ?? 0} 个步骤`, `${round.tools ?? 0} 个工具调用`];
  const duration = formatDuration(round);
  if (duration) parts.push(duration);
  if (round.startedAt) parts.push(formatTime(round.startedAt));
  meta.textContent = parts.join(' · ');
  summary.append(label, user, state, meta);
  const previous = details.querySelector(':scope > summary');
  if (previous) details.replaceChild(summary, previous);
  else details.insertBefore(summary, details.firstChild);
}

function findBody(runId) {
  const details = [...roundsEl.children].find((node) => node.dataset.runId === runId);
  return details?.querySelector('.trace-round-body') ?? null;
}

async function refreshRound(runId, body) {
  if (!canvasId || loading.has(runId)) return;
  loading.add(runId);
  try {
    const response = await fetch(`/api/canvases/${canvasId}/rounds/${runId}`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    const rows = data.rows ?? [];
    expanded.set(runId, rows);
    if (openRounds.has(runId)) {
      // 重建行时保留面板滚动位置，避免用户正在查看的内容被顶走。
      const scrollTop = panel.scrollTop;
      body.replaceChildren(...rowNodes(rows));
      panel.scrollTop = scrollTop;
    }
  } catch {
    if (!expanded.has(runId)) body.textContent = '执行记录加载失败，展开后重试';
  } finally {
    loading.delete(runId);
  }
}

function rowNodes(rows) {
  return rows.map((row) => {
    const item = document.createElement('div');
    item.className = 'trace-row ' + row.kind;
    item.dataset.status = row.status ?? '';
    const head = document.createElement('div');
    head.className = 'trace-row-head';
    const kind = document.createElement('span');
    kind.className = 'trace-kind';
    kind.textContent = KIND_LABEL[row.kind] ?? row.kind;
    const title = document.createElement('span');
    title.className = 'trace-row-title';
    title.textContent = row.title ?? KIND_LABEL[row.kind] ?? row.kind;
    head.append(kind, title);
    if (row.kind === 'model' && row.input) {
      const model = document.createElement('code');
      model.className = 'trace-model';
      model.textContent = row.input;
      head.append(model);
    }
    if (row.status) {
      const state = document.createElement('span');
      state.className = 'trace-pill';
      state.dataset.status = row.status;
      state.textContent = ROW_STATUS[row.status] ?? row.status;
      head.append(state);
    }
    const meta = document.createElement('span');
    meta.className = 'trace-row-meta';
    const parts = [];
    if (row.usage) parts.push(`${row.usage.inputTokens ?? 0}/${row.usage.outputTokens ?? 0} tokens`);
    if (formatDuration(row)) parts.push(formatDuration(row));
    if (row.startedAt) parts.push(formatTime(row.startedAt));
    meta.textContent = parts.join(' · ');
    if (meta.textContent) head.append(meta);
    item.append(head);
    if (row.thinking) item.append(textNode('思考过程', row.thinking, false));
    if (row.text) item.append(textNode(row.kind === 'assistant' ? '回复内容' : '内容', row.text, row.kind === 'assistant'));
    if (row.input && row.kind === 'tool') item.append(ioNode('输入参数', row.input, row.status === 'failed'));
    if (row.output) item.append(ioNode('执行结果', row.output, row.status === 'failed'));
    return item;
  });
}

function textNode(label, text, open) {
  const details = document.createElement('details');
  details.className = 'trace-io trace-text';
  details.open = open;
  const summary = document.createElement('summary');
  summary.textContent = `${label} · ${text.replace(/\s+/g, ' ')}`;
  const content = document.createElement('div');
  content.className = 'trace-row-text';
  content.textContent = text;
  details.append(summary, content);
  return details;
}

function ioNode(label, value, open) {
  const details = document.createElement('details');
  details.className = 'trace-io';
  details.open = open;
  const summary = document.createElement('summary');
  summary.textContent = label;
  const pre = document.createElement('pre');
  pre.className = 'trace-row-io';
  pre.textContent = value;
  details.append(summary, pre);
  return details;
}

function emptyNode() {
  const empty = document.createElement('p');
  empty.className = 'trace-empty';
  empty.textContent = '暂无执行记录，发送消息后可在这里查看 Agent 的每一步。';
  return empty;
}
