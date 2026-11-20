import { renderRounds, setCanvas } from './trajectory.js';

const messages = document.getElementById('chat-messages');
const form = document.getElementById('chat-form');
const input = document.getElementById('chat-input');
const sendButton = document.getElementById('chat-send');
const statusBadge = document.getElementById('chat-status');
const backLatest = document.getElementById('chat-back-latest');
const tabs = [...document.querySelectorAll('.chat-tab')];
const tracePanel = document.getElementById('trace-panel');
const skillMenu = document.getElementById('skill-menu');
const skillChip = document.getElementById('skill-chip');
const skillChipName = document.getElementById('skill-chip-name');
const skillChipClear = document.getElementById('skill-chip-clear');
const imageInput = document.getElementById('chat-image-input');
const imageAdd = document.getElementById('chat-image-add');
const imageBar = document.getElementById('chat-images');

// 斜杠命令选择 Skill：列表来自 /api/skills，提交时把真实 skillId 传给 Agent。
let skillOptions = [];
let skillFiltered = [];
let skillIndex = 0;
let selectedSkill = null;
const MAX_IMAGES = 3;
let pendingImages = [];

// 与工作区前端一致的状态文案，状态本身全部来自服务端事件。
const LABEL = {
  queued: '排队', starting: '准备中', running: '执行中', generating: '生成中', finishing: '收尾中',
  completed: '完成', done: '完成', failed: '失败', error: '失败', interrupted: '已中断',
  cancelled: '已取消', suspended: '等待处理', pending: '等待执行', resolved: '已处理',
  denied: '已拒绝', rejected: '已拒绝', unconfirmed: '结果未确认'
};
const isActive = (status) => ['running', 'generating', 'finishing', 'queued', 'starting'].includes(status);
const isError = (status) => ['error', 'failed'].includes(status);

// 轮询会重建对话 DOM；手动展开/收起按稳定 key 记下，避免每秒被默认状态覆盖。
const openChoice = new Map();
let historyHead = -1;
let followTail = true;

function showTab(tab) {
  for (const button of tabs) {
    const active = button.dataset.tab === tab;
    button.setAttribute('aria-selected', String(active));
  }
  messages.hidden = tab !== 'chat';
  tracePanel.hidden = tab !== 'trace';
  if (tab === 'chat') requestAnimationFrame(() => {
    if (followTail) messages.scrollTop = messages.scrollHeight;
    syncFollow();
  });
}

for (const button of tabs) {
  button.addEventListener('click', () => showTab(button.dataset.tab));
}

const pad = (n) => String(n).padStart(2, '0');

const formatTime = (at) => {
  if (!at) return '';
  const date = new Date(at);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
};

function timingNode(timestampMs, durationMs) {
  const wrapper = document.createElement('span');
  wrapper.className = 'chat-turn-timing';
  if (durationMs !== undefined && durationMs >= 0) {
    const duration = document.createElement('span');
    duration.className = 'chat-duration';
    duration.textContent = durationMs < 1000 ? `${Math.round(durationMs)}ms` : `${(durationMs / 1000).toFixed(1)}s`;
    wrapper.append(duration);
  }
  if (timestampMs) {
    const time = document.createElement('time');
    time.className = 'chat-timestamp';
    time.textContent = formatTime(timestampMs);
    wrapper.append(time);
  }
  return wrapper;
}

function userNode(text, at) {
  const article = document.createElement('article');
  article.className = 'chat-user-message';
  const caption = document.createElement('div');
  caption.className = 'chat-user-caption';
  const who = document.createElement('span');
  who.textContent = '用户';
  caption.append(who);
  const time = document.createElement('time');
  time.className = 'chat-timestamp';
  time.textContent = formatTime(at);
  if (at) caption.append(time);
  const copy = document.createElement('button');
  copy.type = 'button';
  copy.className = 'chat-copy';
  copy.textContent = '复制';
  copy.title = '复制这条消息';
  copy.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(text);
      copy.textContent = '已复制';
    } catch {
      copy.textContent = '复制失败';
    }
    window.setTimeout(() => { copy.textContent = '复制'; }, 1500);
  });
  caption.append(copy);
  const bubble = document.createElement('div');
  bubble.className = 'chat-user-bubble';
  bubble.textContent = text;
  article.append(caption, bubble);
  return article;
}

async function prepareImage(file) {
  const limit = 1.5 * 1024 * 1024;
  try {
    const bitmap = await createImageBitmap(file);
    const maxSide = Math.max(bitmap.width, bitmap.height);
    if (file.size <= limit && maxSide <= 2048) {
      bitmap.close?.();
      return file;
    }
    const scale = Math.min(1, 2048 / maxSide);
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close?.();
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85));
    if (!blob || blob.size >= file.size) return file;
    return new File([blob], 'image.jpg', { type: 'image/jpeg' });
  } catch {
    return file;
  }
}

async function addImages(files) {
  const room = Math.max(0, MAX_IMAGES - pendingImages.length);
  const images = [...files].filter((file) => file.type.startsWith('image/')).slice(0, room);
  if (!images.length) return;
  for (const file of images) {
    if (pendingImages.length >= MAX_IMAGES) break;
    const prepared = await prepareImage(file);
    pendingImages.push({ file: prepared, url: URL.createObjectURL(prepared) });
  }
  renderImages();
}

function renderImages() {
  imageBar.replaceChildren();
  imageBar.hidden = pendingImages.length === 0;
  pendingImages.forEach((item, index) => {
    const chip = document.createElement('figure');
    chip.className = 'chat-image-chip';
    const image = document.createElement('img');
    image.src = item.url;
    image.alt = '待发送图片 ' + (index + 1);
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'chat-image-remove';
    remove.textContent = '×';
    remove.setAttribute('aria-label', '移除图片');
    remove.addEventListener('click', () => {
      URL.revokeObjectURL(item.url);
      pendingImages.splice(index, 1);
      renderImages();
    });
    chip.append(image, remove);
    imageBar.append(chip);
  });
}

function clearImages() {
  for (const item of pendingImages) URL.revokeObjectURL(item.url);
  pendingImages = [];
  renderImages();
}

async function uploadImages(canvasId) {
  const images = [];
  for (const item of [...pendingImages]) {
    const data = new FormData();
    data.append('file', item.file, item.file.name || 'image.png');
    const response = await fetch(`/api/canvases/${canvasId}/images`, { method: 'POST', body: data });
    if (!response.ok) {
      let message = '图片上传失败，请重试。';
      try {
        const body = await response.json();
        if (body?.message) message = body.message;
      } catch {
        /* 保留默认提示 */
      }
      throw new Error(message);
    }
    const body = await response.json();
    if (!body?.file) throw new Error('图片上传失败，请重试。');
    images.push(body.file);
  }
  return images;
}

imageAdd.addEventListener('click', () => imageInput.click());
imageInput.addEventListener('change', () => {
  addImages(imageInput.files);
  imageInput.value = '';
});
input.addEventListener('paste', (event) => {
  const files = [...(event.clipboardData?.files ?? [])].filter((file) => file.type.startsWith('image/'));
  if (!files.length) return;
  event.preventDefault();
  addImages(files);
});

function statusNode(status, always = false) {
  if (!status) return null;
  if (!always && !isActive(status) && !isError(status)) return null;
  const badge = document.createElement('span');
  badge.className = 'chat-turn-status';
  badge.dataset.active = String(isActive(status));
  if (isActive(status)) {
    const dot = document.createElement('i');
    dot.className = 'chat-live-dot';
    badge.append(dot);
  }
  badge.append(LABEL[status] ?? status);
  return badge;
}

const nearBottom = () => messages.scrollHeight - messages.scrollTop - messages.clientHeight < 48;

function syncFollow() {
  if (!messages.hidden) followTail = nearBottom();
  backLatest.hidden = followTail || !messages.childElementCount;
}

function scrollToLatest(force = false) {
  if (force || followTail) {
    messages.scrollTop = messages.scrollHeight;
    followTail = true;
  }
  backLatest.hidden = followTail || !messages.childElementCount;
}

messages.addEventListener('scroll', syncFollow, { passive: true });
backLatest.addEventListener('click', () => scrollToLatest(true));

// details 的默认开合由块状态决定；用户手动操作过的状态优先，跨轮询重建保留。
function applyOpen(details, key, defaultOpen) {
  details.dataset.key = key;
  details.dataset.defaultOpen = String(defaultOpen);
  details.open = openChoice.get(key) ?? defaultOpen;
  details.addEventListener('toggle', () => {
    if (details.open === (details.dataset.defaultOpen === 'true')) openChoice.delete(key);
    else openChoice.set(key, details.open);
  });
}

// 与工作区 ChatTimeline 相同的块渲染：TOOL / REASONING / ANSWER。
function blockNode(block, turn, index) {
  const row = document.createElement('div');
  row.className = 'chat-row chat-' + block.kind;
  row.dataset.status = block.status ?? '';
  const body = document.createElement('div');
  body.className = 'chat-row-body';
  const meta = document.createElement('div');
  meta.className = 'chat-row-meta';
  const channel = document.createElement('span');
  channel.className = 'chat-channel';
  if (block.kind === 'tool') {
    channel.textContent = 'TOOL';
    meta.append(channel);
    const name = document.createElement('code');
    name.className = 'chat-tool-name';
    name.textContent = block.name ?? '工具调用';
    meta.append(name);
    const state = document.createElement('span');
    state.className = 'chat-block-status';
    state.textContent = LABEL[block.status] ?? '状态未提供';
    meta.append(state);
    const started = block.startedAt;
    const ended = block.endedAt;
    meta.append(timingNode(started ?? block.timestampMs,
      ended !== undefined && started !== undefined ? ended - started : undefined));
    const caret = document.createElement('span');
    caret.className = 'chat-caret';
    caret.textContent = '⌄';
    meta.append(caret);
    const details = document.createElement('details');
    applyOpen(details, `${turn.runId}:tool:${block.name ?? ''}:${started ?? block.timestampMs ?? index}`, isError(block.status));
    const summary = document.createElement('summary');
    summary.append(meta);
    details.append(summary);
    if (block.input) {
      const detail = document.createElement('div');
      detail.className = 'chat-tool-detail';
      const label = document.createElement('span');
      label.textContent = '输入参数';
      const pre = document.createElement('pre');
      pre.textContent = block.input;
      detail.append(label, pre);
      details.append(detail);
    }
    if (block.output) {
      const detail = document.createElement('div');
      detail.className = 'chat-tool-detail';
      const label = document.createElement('span');
      label.textContent = '执行结果';
      const pre = document.createElement('pre');
      pre.textContent = block.output;
      detail.append(label, pre);
      details.append(detail);
    }
    if (!block.input && !block.output) {
      const empty = document.createElement('p');
      empty.className = 'chat-muted';
      empty.textContent = isActive(block.status) ? '执行中，结果尚未返回' : '未记录执行结果';
      details.append(empty);
    }
    body.append(details);
    row.append(body);
    return row;
  }
  if (block.kind === 'reasoning') {
    channel.textContent = 'REASONING';
    meta.append(channel);
    const state = statusNode(block.status);
    if (state) meta.append(state);
    meta.append(timingNode(block.timestampMs));
    body.append(meta);
    const details = document.createElement('details');
    // 生成过程中自动展开，结束后按块状态自动收起；用户手动操作优先。
    applyOpen(details, `${turn.runId}:reasoning:${block.timestampMs ?? index}`, isActive(block.status));
    const summary = document.createElement('summary');
    summary.className = 'chat-reasoning-summary';
    const caret = document.createElement('span');
    caret.className = 'chat-caret';
    caret.textContent = '▸';
    const peek = document.createElement('span');
    peek.className = 'chat-reasoning-peek';
    peek.textContent = (block.content ?? '').replace(/\s+/g, ' ');
    summary.append(caret, peek);
    details.append(summary);
    const content = document.createElement('div');
    content.className = 'chat-reasoning-content';
    content.textContent = block.content ?? '';
    details.append(content);
    body.append(details);
    row.append(body);
    return row;
  }
  channel.textContent = 'ANSWER';
  meta.append(channel);
  const state = statusNode(block.status);
  if (state) meta.append(state);
  meta.append(timingNode(block.timestampMs));
  body.append(meta);
  const content = document.createElement('div');
  content.className = 'chat-answer-content';
  content.textContent = block.content ?? '';
  body.append(content);
  row.append(body);
  return row;
}

// 轮次结束后只保留最终回复；中间思考、工具和过程性回复收进可展开的执行过程。
function processNode(blocks, turn) {
  const details = document.createElement('details');
  details.className = 'chat-turn-process';
  applyOpen(details, `${turn.runId}:process`, false);
  const summary = document.createElement('summary');
  const caret = document.createElement('span');
  caret.className = 'chat-caret';
  caret.textContent = '▸';
  const channel = document.createElement('span');
  channel.className = 'chat-channel';
  channel.textContent = 'PROCESS';
  const label = document.createElement('span');
  label.className = 'chat-process-label';
  const tools = blocks.filter((block) => block.kind === 'tool').length;
  label.textContent = `执行过程 · ${blocks.length} 个步骤${tools ? ` · ${tools} 次工具调用` : ''}`;
  summary.append(caret, channel, label);
  // 正常完成不再重复状态；失败或中断时在摘要上直接提示。
  if (!['completed', 'done'].includes(turn.status)) {
    const state = statusNode(turn.status, true);
    if (state) summary.append(state);
  }
  details.append(summary);
  const body = document.createElement('div');
  body.className = 'chat-turn-process-body';
  blocks.forEach((block, index) => body.append(blockNode(block, turn, index)));
  details.append(body);
  return details;
}

// 对话区只渲染日志投影出的真实内容。
function renderHistory(history) {
  historyHead = history.head;
  const pinned = followTail;
  const top = messages.scrollTop;
  messages.replaceChildren();
  for (const turn of history.turns ?? []) {
    if (turn.user) messages.append(userNode(turn.user, turn.userAt));
    const blocks = (turn.blocks ?? []).filter((block) => block.content || block.kind === 'tool');
    if (!blocks.length && !isActive(turn.status)) continue;
    const article = document.createElement('article');
    article.className = 'chat-assistant-message';
    const header = document.createElement('header');
    header.className = 'chat-assistant-header';
    const duration = turn.endedAt !== undefined && turn.startedAt !== undefined ? turn.endedAt - turn.startedAt : undefined;
    header.append(timingNode(turn.startedAt, duration));
    const state = statusNode(turn.status, true);
    if (state) header.append(state);
    article.append(header);
    if (!blocks.length && isActive(turn.status)) {
      const wait = document.createElement('p');
      wait.className = 'chat-process-notice';
      wait.textContent = '正在处理你的要求…';
      article.append(wait);
    }
    if (isActive(turn.status)) {
      // 执行中保持逐步实时展示。
      blocks.forEach((block, index) => article.append(blockNode(block, turn, index)));
    } else {
      // 结束后主视图只展示最终回复；执行过程折叠在最终回复上方。
      const finalAnswer = [...blocks].reverse().find((block) => block.kind === 'answer' && block.content);
      const processBlocks = finalAnswer ? blocks.filter((block) => block !== finalAnswer) : blocks;
      if (processBlocks.length) article.append(processNode(processBlocks, turn));
      if (finalAnswer) article.append(blockNode(finalAnswer, turn, blocks.indexOf(finalAnswer)));
    }
    messages.append(article);
  }
  // 用户停留在底部时持续跟随；翻看历史时不强行拉回，改用“回到最新”。
  if (!messages.hidden) {
    if (pinned) messages.scrollTop = messages.scrollHeight;
    else messages.scrollTop = Math.min(top, Math.max(0, messages.scrollHeight - messages.clientHeight));
  }
  followTail = pinned;
  backLatest.hidden = pinned || !messages.childElementCount;
}

async function waitForEditor() {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (window.__editorApi && window.__editorApi.ready) return window.__editorApi;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error('editor not ready');
}

async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) return null;
  return response.json();
}

const SLASH_QUERY = /^\/([^\s]*)$/;

function hideSkillMenu() {
  skillMenu.hidden = true;
  skillMenu.replaceChildren();
  skillFiltered = [];
  skillIndex = 0;
}

function renderSkillMenu(query) {
  const keyword = (query ?? '').toLowerCase();
  skillFiltered = skillOptions.filter((skill) =>
    skill.name.toLowerCase().includes(keyword) ||
    (skill.description ?? '').toLowerCase().includes(keyword));
  skillIndex = Math.min(skillIndex, Math.max(0, skillFiltered.length - 1));
  if (!skillFiltered.length) {
    const empty = document.createElement('p');
    empty.className = 'skill-empty';
    empty.textContent = '没有匹配的 Skill，直接发送将按默认流程执行';
    skillMenu.replaceChildren(empty);
    skillMenu.hidden = false;
    return;
  }
  skillMenu.replaceChildren(...skillFiltered.map((skill, index) => {
    const option = document.createElement('button');
    option.type = 'button';
    option.className = 'skill-option' + (index === skillIndex ? ' active' : '');
    const name = document.createElement('strong');
    name.textContent = '/' + skill.name;
    const description = document.createElement('small');
    description.textContent = skill.description || skill.id;
    option.append(name, description);
    option.addEventListener('mousedown', (event) => {
      event.preventDefault();
      selectSkill(skill);
    });
    return option;
  }));
  skillMenu.hidden = false;
}

function syncSkillMenu() {
  const match = SLASH_QUERY.exec(input.value);
  if (!match) {
    hideSkillMenu();
    return;
  }
  renderSkillMenu(match[1]);
}

function selectSkill(skill) {
  selectedSkill = skill;
  skillChipName.textContent = skill.name;
  skillChip.title = skill.description || skill.id;
  skillChip.hidden = false;
  input.value = '';
  hideSkillMenu();
  input.focus();
}

function clearSkill() {
  selectedSkill = null;
  skillChip.hidden = true;
  skillChipName.textContent = '';
  skillChip.removeAttribute('title');
}

async function loadSkills() {
  const body = await fetchJson('/api/skills');
  if (Array.isArray(body)) skillOptions = body;
}

input.addEventListener('input', syncSkillMenu);
skillChipClear.addEventListener('click', () => {
  clearSkill();
  input.focus();
});

async function refreshHistory(editor, force = false) {
  const history = await fetchJson(`/api/canvases/${editor.canvasId}/history`);
  if (!history?.turns) return;
  if (!force && history.head === historyHead) return;
  renderHistory(history);
}

async function refreshRounds(editor) {
  const body = await fetchJson(`/api/canvases/${editor.canvasId}/rounds`);
  if (body?.rounds) renderRounds(body.rounds);
}

input.addEventListener('keydown', (event) => {
  if (!skillMenu.hidden) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (skillFiltered.length) {
        const step = event.key === 'ArrowDown' ? 1 : -1;
        skillIndex = (skillIndex + step + skillFiltered.length) % skillFiltered.length;
        renderSkillMenu(SLASH_QUERY.exec(input.value)?.[1] ?? '');
      }
      return;
    }
    if (event.key === 'Enter' && !event.isComposing) {
      event.preventDefault();
      const skill = skillFiltered[skillIndex];
      if (skill) selectSkill(skill);
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      hideSkillMenu();
      return;
    }
  }
  if (event.key !== 'Enter' || event.isComposing) return;
  if (event.shiftKey) return;
  event.preventDefault();
  form.requestSubmit();
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = input.value.trim();
  if ((!text && !pendingImages.length) || !window.__editorApi || window.__editorApi.busy) return;
  input.value = '';
  let timer = 0;
  try {
    const editor = await waitForEditor();
    setCanvas(editor.canvasId);
    await refreshRounds(editor);
    editor.setBusy(true);
    sendButton.disabled = true;
    imageAdd.disabled = true;
    statusBadge.textContent = '执行中';
    messages.append(userNode(text || '（图片）', 0));
    scrollToLatest(true);
    const images = await uploadImages(editor.canvasId);
    clearImages();
    timer = window.setInterval(async () => {
      await refreshHistory(editor);
      await refreshRounds(editor);
    }, 1000);
    const skillId = selectedSkill?.id ?? null;
    clearSkill();
    const response = await fetch(`/api/canvases/${editor.canvasId}/generate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text, skillId, images })
    });
    if (!response.ok) {
      let message = '修改失败，请重试。';
      try {
        const body = await response.json();
        if (body?.message) message = body.message;
      } catch {
        /* 保留默认提示 */
      }
      const error = document.createElement('p');
      error.className = 'chat-process-notice';
      error.textContent = message;
      messages.append(error);
      scrollToLatest(true);
      return;
    }
    await refreshHistory(editor, true);
    await refreshRounds(editor);
    await editor.reloadFromServer();
  } catch (error) {
    const notice = document.createElement('p');
    notice.className = 'chat-process-notice';
    notice.textContent = '请求中断：' + (error?.message || '网络错误') + '，请重新提交。';
    messages.append(notice);
    scrollToLatest(true);
  } finally {
    window.clearInterval(timer);
    if (window.__editorApi) window.__editorApi.setBusy(false);
    sendButton.disabled = false;
    imageAdd.disabled = false;
    statusBadge.textContent = '空闲';
  }
});

// 打开页面时用日志恢复对话与轨迹，并加载可选的 Skill 列表。
(async () => {
  loadSkills().catch(() => { /* Skill 列表加载失败时退回默认流程 */ });
  try {
    const editor = await waitForEditor();
    setCanvas(editor.canvasId);
    await refreshHistory(editor, true);
    await refreshRounds(editor);
  } catch {
    /* 编辑器未就绪时静默 */
  }
})();
