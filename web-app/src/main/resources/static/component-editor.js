const CANVAS_KEY = 'hb.component-studio.canvas-id';
const DRAG_TYPE = 'application/x-hb-component';
const canvasSizes = { '960x600': [960, 600], '1280x720': [1280, 720], '1920x1080': [1920, 1080], '390x844': [390, 844] };
const TIMELINE_DURATION = 10000;
const effectNames = { none: '无动画', shadow: '阴影加重', scale: '放大', rotate: '旋转一圈' };
const CUSTOM_COLORS_KEY = 'hb.component-studio.custom-colors.v1';
const basicColors = [
  '#f08080', '#e62828', '#79534b', '#914545', '#651e12', '#99ffff', '#7edbe4', '#3b82f6', '#0015e8', '#080897', '#20457c', '#080852',
  '#ffff99', '#fff34a', '#eeb069', '#e87939', '#8a451b', '#88852b', '#8b81ee', '#8162d6', '#367fa4', '#07033f', '#41175c', '#360333',
  '#bcff8b', '#9fea43', '#53f244', '#40ed5a', '#b7ffb0', '#888d63', '#e293c9', '#e166d4', '#e51cf0', '#e8328d', '#8584bd', '#7c214d',
  '#348d29', '#487d4a', '#518a8b', '#44828f', '#1b4249', '#0c3238', '#6d2993', '#460a72', '#000000', '#808080', '#c0c0c0', '#ffffff'
];
let canvasWidth = 960;
let canvasHeight = 600;
const ID_CHARS = 'abcdefghjkmnpqrstuvwxyz23456789';
function generateId() {
  for (;;) {
    let id = '';
    for (let i = 0; i < 5; i++) id += ID_CHARS[crypto.getRandomValues(new Uint8Array(1))[0] & 31];
    if (!page.components.some(item => item.id === id)) return id;
  }
}

const definitions = {
  button: { name: '按钮', width: 120, height: 44, content: '按钮' },
  text: { name: '文本', width: 200, height: 48, content: '文本内容' },
  card: { name: '卡片', width: 240, height: 140, content: '卡片标题', description: '卡片描述' },
  heading: { name: '标题', width: 280, height: 56, content: '页面标题' },
  tag: { name: '标签', width: 100, height: 32, content: '标签' },
  divider: { name: '分隔线', width: 240, height: 24, content: '' }
};
const byId = (id) => document.getElementById(id);
const canvas = byId('canvas');
const saveStatus = byId('save-status');
const form = byId('inspector-form');
const contentInput = byId('component-content');
const descriptionInput = byId('component-description');
const triggerInput = byId('animation-trigger');
const effectInput = byId('animation-effect');
const deleteButton = byId('delete-component');
const libraryItems = document.querySelectorAll('.library-item');
const modeButtons = document.querySelectorAll('.mode-switch button');
const workbench = document.querySelector('.workbench');
const nodes = new Map();
const animations = new Map();
const timelineRows = new Map();
const timelineAnimations = [];
let page = { version: 1, canvasSize: '960x600', components: [] };
let selectedId = null;
let mode = 'edit';
let drag = null;
let saveTimer;
let dirty = false;
let timelineDrag = null;
let timelineTime = 0;
let timelinePlaying = false;
let timelineFrame;
let timelineStart = 0;
let panelResize = null;
let zoomMode = 'width';
let zoomScale = 1;
let inlineEdit = null;
let colorProperty = 'textColor';
let colorDraft = { h: 0, s: 0, v: 0, hex: '#000000', useDefault: false };
let selectedCustomColor = -1;
let customColors = [];
try {
  const storedColors = JSON.parse(localStorage.getItem(CUSTOM_COLORS_KEY) || '[]');
  if (Array.isArray(storedColors)) customColors = storedColors.slice(0, 24).map(color =>
    typeof color === 'string' && /^#[0-9a-f]{6}$/i.test(color) ? color.toLowerCase() : null);
} catch { /* 自定义颜色读取失败时忽略；画布数据由 loadFromServer 加载。 */ }

function savePage() {
  clearTimeout(saveTimer);
  if (!dirty || drag || timelineDrag) return;
  dirty = false;
  saveStatus.textContent = '正在保存…';
  saveStatus.dataset.state = 'pending';
  fetch(`/api/canvases/${canvasId}/page`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(page),
    keepalive: true
  }).then(response => {
    if (!response.ok) throw new Error('save failed');
    saveStatus.textContent = '已保存到服务端';
    saveStatus.dataset.state = 'saved';
  }).catch(() => {
    saveStatus.textContent = '保存失败，请检查服务';
    saveStatus.dataset.state = 'error';
  });
}

function changed(delay = 0) {
  dirty = true;
  clearTimeout(saveTimer);
  saveStatus.textContent = '正在保存…';
  saveStatus.dataset.state = 'pending';
  if (delay) saveTimer = setTimeout(savePage, delay);
  else savePage();
}

function validatePageData(data) {
  const ids = new Set();
  const fields = ['id', 'type', 'x', 'y', 'width', 'height', 'content', 'description',
    'animation', 'timeline', 'textColor', 'backgroundColor'];
  if (data?.version !== 1 || !Array.isArray(data.components)) return '画布数据无效';
  const size = data.canvasSize ?? '960x600';
  if (!Object.hasOwn(canvasSizes, size)) return '画布尺寸无效';
  const [width, height] = canvasSizes[size];
  for (const item of data.components) {
    if (!item || typeof item.id !== 'string' || !item.id || ids.has(item.id) ||
        !Object.hasOwn(definitions, item.type)) return '组件 ID 或类型无效';
    for (const key of Object.keys(item)) {
      if (!fields.includes(key)) return '组件 ' + item.id + ' 包含未知字段 ' + key;
    }
    const definition = definitions[item.type];
    item.width ??= definition.width;
    item.height ??= definition.height;
    if (!Number.isFinite(item.x) || !Number.isFinite(item.y) || item.x < 0 || item.y < 0 ||
        !Number.isFinite(item.width) || !Number.isFinite(item.height) || item.width < 40 ||
        item.height < (item.type === 'divider' ? 12 : 24) || item.width > width || item.height > height ||
        item.x > width - item.width || item.y > height - item.height ||
        typeof item.content !== 'string' || item.content.length > 200 ||
        (item.type === 'card' && (typeof item.description !== 'string' || item.description.length > 500)) ||
        !['click', 'hover'].includes(item.animation?.trigger) ||
        !['none', 'shadow', 'scale', 'rotate'].includes(item.animation?.effect)) return '组件配置无效';
    ids.add(item.id);
    item.timeline ??= { effect: 'none', start: 0, duration: 700 };
    if (!Object.hasOwn(effectNames, item.timeline.effect) || !Number.isFinite(item.timeline.start) ||
        !Number.isFinite(item.timeline.duration) || item.timeline.start < 0 || item.timeline.duration < 100 ||
        item.timeline.start + item.timeline.duration > TIMELINE_DURATION) return '时间轴配置无效';
    for (const property of ['textColor', 'backgroundColor']) {
      if (item[property] !== undefined && (typeof item[property] !== 'string' || !/^#[0-9a-f]{6}$/i.test(item[property]))) return '颜色格式无效';
    }
  }
  return null;
}

function rerender() {
  for (const animation of animations.values()) animation.cancel();
  animations.clear();
  for (const [id, node] of nodes) node.remove();
  nodes.clear();
  timelineRows.clear();
  selectComponent(null);
  for (const item of page.components) mountComponent(item);
  updateCanvasSummary();
  byId('canvas-size').value = page.canvasSize;
  fitCanvas();
}

async function loadFromServer() {
  const response = await fetch(`/api/canvases/${canvasId}/page`);
  if (!response.ok) throw new Error('无法从服务端读取画布');
  const data = await response.json();
  const error = validatePageData(data);
  if (error) throw new Error(error);
  page = data;
  let converted = false;
  for (const item of page.components) {
    if (item.id.length !== 5) {
      item.id = generateId();
      converted = true;
    }
  }
  if (converted) savePage();
  [canvasWidth, canvasHeight] = canvasSizes[data.canvasSize];
  saveStatus.textContent = '已从服务端加载';
}

async function reloadFromServer() {
  await loadFromServer();
  rerender();
}

function updateComponent(item) {
  const node = nodes.get(item.id);
  node.style.left = item.x + 'px';
  node.style.top = item.y + 'px';
  node.style.width = item.width + 'px';
  node.style.height = item.height + 'px';
  node.setAttribute('aria-label', definitions[item.type].name + (item.content ? '：' + item.content : ''));
  const body = node.firstElementChild;
  body.style.color = item.textColor || '';
  body.style.backgroundColor = item.type === 'divider' ? '' : item.backgroundColor || '';
  body.style.setProperty('--component-text-color', item.textColor || '');
  body.style.setProperty('--component-line-color', item.type === 'divider' ? item.backgroundColor || '' : '');
  if (item.type === 'card') {
    body.querySelector('strong').textContent = item.content;
    body.querySelector('p').textContent = item.description;
  } else body.textContent = item.content;
  const row = timelineRows.get(item.id);
  if (row) row.querySelector('.track-name').textContent = definitions[item.type].name + (item.content ? ' · ' + item.content : '');
  if (selectedId === item.id) {
    byId('component-width').value = item.width;
    byId('component-height').value = item.height;
    byId('component-width').max = canvasWidth - item.x;
    byId('component-height').max = canvasHeight - item.y;
  }
}

function updateAnimationDescription() {
  const descriptions = {
    click: { none: '未设置动画', shadow: '加深 200ms，停留 400ms，恢复 200ms', scale: '放大至 1.08 倍，然后恢复，共 400ms', rotate: '700ms 旋转一圈' },
    hover: { none: '未设置动画', shadow: '悬停时加深，移开后恢复，各 200ms', scale: '悬停时放大至 1.08 倍，移开后恢复', rotate: '700ms 旋转一圈，移开时恢复' }
  };
  byId('animation-description').textContent = descriptions[triggerInput.value][effectInput.value];
}

function selectComponent(id) {
  if (inlineEdit && inlineEdit.item.id !== id) finishInlineEdit(false);
  if (byId('color-palette').open) byId('color-palette').close();
  selectedId = id;
  const selected = page.components.find((item) => item.id === id);
  for (const [itemId, node] of nodes) node.classList.toggle('selected', itemId === id);
  form.hidden = !selected;
  byId('inspector-empty').hidden = Boolean(selected);
  byId('selection-label').hidden = !selected;
  deleteButton.hidden = !selected;
  updateTimelineSelection();
  if (!selected) return;
  byId('selection-label').textContent = definitions[selected.type].name;
  byId('content-fields').hidden = selected.type === 'divider';
  byId('inspector-fields').classList.toggle('no-content', selected.type === 'divider');
  byId('content-label').textContent = selected.type === 'card' ? '卡片标题' : '文字内容';
  byId('description-field').hidden = selected.type !== 'card';
  byId('component-width').value = selected.width;
  byId('component-width').max = canvasWidth - selected.x;
  byId('component-height').value = selected.height;
  byId('component-height').min = selected.type === 'divider' ? 12 : 24;
  byId('component-height').max = canvasHeight - selected.y;
  contentInput.value = selected.content;
  descriptionInput.value = selected.description || '';
  triggerInput.value = selected.animation.trigger;
  effectInput.value = selected.animation.effect;
  updateColorControls(selected);
  updateAnimationDescription();
}

function updateColorControls(item) {
  const body = nodes.get(item.id).firstElementChild;
  const style = getComputedStyle(body);
  const textButton = byId('text-color-button');
  const backgroundButton = byId('background-color-button');
  textButton.hidden = item.type === 'divider';
  textButton.querySelector('i').style.backgroundColor = item.textColor || style.color;
  textButton.querySelector('code').textContent = item.textColor || '默认';
  backgroundButton.querySelector('i').style.backgroundColor = item.backgroundColor ||
    (item.type === 'divider' ? getComputedStyle(body, '::before').backgroundColor : style.backgroundColor);
  backgroundButton.querySelector('code').textContent = item.backgroundColor || '默认';
  byId('background-color-label').textContent = item.type === 'divider' ? '线条' : '背景';
}

function applyComponentColor(color) {
  if (mode !== 'edit') return;
  const item = page.components.find(entry => entry.id === selectedId);
  if (!item) return;
  if (color) item[colorProperty] = color;
  else delete item[colorProperty];
  updateComponent(item);
  updateColorControls(item);
  changed();
  byId('color-palette').close();
  byId(colorProperty === 'textColor' ? 'text-color-button' : 'background-color-button').focus({ preventScroll: true });
}

function rgbToHex(rgb) {
  return '#' + rgb.map(channel => Math.round(channel).toString(16).padStart(2, '0')).join('');
}

function hsvToRgb(h, s, v) {
  const channel = (offset) => {
    const k = (offset + h / 60) % 6;
    return 255 * (v - v * s * Math.max(0, Math.min(k, 4 - k, 1)));
  };
  return [channel(5), channel(3), channel(1)].map(Math.round);
}

function renderColorDraft() {
  const rgb = hsvToRgb(colorDraft.h, colorDraft.s, colorDraft.v);
  colorDraft.hex = rgbToHex(rgb);
  byId('color-hex').value = colorDraft.hex;
  for (const [index, channel] of ['red', 'green', 'blue'].entries()) byId('color-' + channel).value = rgb[index];
  byId('color-preview').style.backgroundColor = colorDraft.hex;
  byId('color-brightness').style.setProperty('--bright-color', rgbToHex(hsvToRgb(colorDraft.h, colorDraft.s, 1)));
  byId('spectrum-cursor').style.left = colorDraft.h / 360 * 100 + '%';
  byId('spectrum-cursor').style.top = (1 - colorDraft.s) * 100 + '%';
  byId('brightness-cursor').style.top = (1 - colorDraft.v) * 100 + '%';
  byId('color-spectrum').setAttribute('aria-valuenow', Math.round(colorDraft.h));
  byId('color-spectrum').setAttribute('aria-valuetext', '色相 ' + Math.round(colorDraft.h) + ' 度，饱和度 ' + Math.round(colorDraft.s * 100) + '%');
  byId('color-brightness').setAttribute('aria-valuenow', Math.round(colorDraft.v * 100));
  byId('color-error').textContent = '';
  byId('color-confirm').disabled = false;
  for (const swatch of byId('basic-colors').children) swatch.setAttribute('aria-pressed', String(swatch.dataset.color === colorDraft.hex));
}

function setColorDraft(hex) {
  const rgb = [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16) / 255);
  const max = Math.max(...rgb), min = Math.min(...rgb), delta = max - min;
  let hue = colorDraft.h;
  if (delta) {
    hue = max === rgb[0] ? (rgb[1] - rgb[2]) / delta : max === rgb[1] ? (rgb[2] - rgb[0]) / delta + 2 : (rgb[0] - rgb[1]) / delta + 4;
    hue = (hue * 60 + 360) % 360;
  }
  colorDraft = { h: hue, s: max ? delta / max : 0, v: max, hex: hex.toLowerCase(), useDefault: false };
  renderColorDraft();
}

function componentColorHex(item, useDefault = false) {
  const body = nodes.get(item.id).firstElementChild;
  const property = colorProperty === 'textColor' ? 'color' : item.type === 'divider' ? '--component-line-color' : 'background-color';
  const previous = body.style.getPropertyValue(property);
  if (useDefault) body.style.removeProperty(property);
  const style = getComputedStyle(body, item.type === 'divider' ? '::before' : null);
  const channels = (colorProperty === 'textColor' ? style.color : style.backgroundColor).match(/[\d.]+/g).map(Number);
  if (useDefault && previous) body.style.setProperty(property, previous);
  return channels.length === 4 && channels[3] === 0 ? '#ffffff' : rgbToHex(channels.slice(0, 3));
}

function renderCustomColors() {
  byId('custom-colors').replaceChildren();
  for (let index = 0; index < 24; index++) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'color-swatch' + (customColors[index] ? '' : ' empty');
    button.dataset.index = index;
    button.style.backgroundColor = customColors[index] || '';
    button.setAttribute('aria-label', '自定义颜色 ' + (index + 1) + (customColors[index] ? ' ' + customColors[index] : '（空）'));
    button.setAttribute('aria-pressed', String(index === selectedCustomColor));
    byId('custom-colors').append(button);
  }
}

for (const color of basicColors) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'color-swatch';
  button.dataset.color = color;
  button.style.backgroundColor = color;
  button.title = color;
  button.setAttribute('aria-label', color);
  byId('basic-colors').append(button);
}
renderCustomColors();
for (const button of document.querySelectorAll('.color-actions button')) button.addEventListener('click', () => {
  if (mode !== 'edit') return;
  const item = page.components.find(entry => entry.id === selectedId);
  if (!item) return;
  colorProperty = button.dataset.colorProperty;
  byId('palette-title').textContent = '编辑颜色 · ' + (colorProperty === 'textColor' ? '文字' : item.type === 'divider' ? '线条' : '背景');
  selectedCustomColor = -1;
  renderCustomColors();
  byId('custom-color-status').textContent = '';
  setColorDraft(componentColorHex(item));
  byId('color-palette').showModal();
  byId('color-hex').focus();
  byId('color-hex').select();
});
byId('color-hex').addEventListener('input', (event) => {
  if (/^#?[0-9a-f]{6}$/i.test(event.target.value)) setColorDraft('#' + event.target.value.replace(/^#/, ''));
  else { byId('color-error').textContent = '请输入 6 位十六进制颜色'; byId('color-confirm').disabled = true; }
});
for (const channel of ['red', 'green', 'blue']) byId('color-' + channel).addEventListener('input', () => {
  const inputs = ['red', 'green', 'blue'].map(name => byId('color-' + name));
  const rgb = inputs.map(input => Number(input.value));
  if (inputs.every((input, index) => input.value !== '' && Number.isInteger(rgb[index]) && rgb[index] >= 0 && rgb[index] <= 255)) setColorDraft(rgbToHex(rgb));
  else { byId('color-error').textContent = 'RGB 为 0–255 的整数'; byId('color-confirm').disabled = true; }
});
for (const id of ['color-spectrum', 'color-brightness']) {
  const target = byId(id);
  const pick = (event) => {
    const rect = target.getBoundingClientRect();
    const y = Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height));
    if (id === 'color-spectrum') {
      colorDraft.h = Math.max(0, Math.min(360, (event.clientX - rect.left) / rect.width * 360));
      colorDraft.s = 1 - y;
    } else colorDraft.v = 1 - y;
    colorDraft.useDefault = false;
    renderColorDraft();
  };
  target.addEventListener('pointerdown', (event) => {
    if (!event.isPrimary || event.button !== 0) return;
    event.preventDefault();
    target.focus();
    target.setPointerCapture(event.pointerId);
    pick(event);
  });
  target.addEventListener('pointermove', (event) => { if (target.hasPointerCapture(event.pointerId)) pick(event); });
  target.addEventListener('pointerup', (event) => { if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId); });
  target.addEventListener('keydown', (event) => {
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
    event.preventDefault();
    const step = event.shiftKey ? 10 : 1;
    if (id === 'color-spectrum' && ['ArrowLeft', 'ArrowRight'].includes(event.key)) colorDraft.h = Math.max(0, Math.min(360, colorDraft.h + (event.key === 'ArrowRight' ? step : -step)));
    else if (['ArrowUp', 'ArrowDown'].includes(event.key)) {
      const property = id === 'color-spectrum' ? 's' : 'v';
      colorDraft[property] = Math.max(0, Math.min(1, colorDraft[property] + (event.key === 'ArrowUp' ? step : -step) / 100));
    }
    colorDraft.useDefault = false;
    renderColorDraft();
  });
}
byId('basic-colors').addEventListener('click', (event) => {
  const button = event.target.closest('[data-color]');
  if (button) setColorDraft(button.dataset.color);
});
byId('custom-colors').addEventListener('click', (event) => {
  const button = event.target.closest('[data-index]');
  if (!button) return;
  selectedCustomColor = Number(button.dataset.index);
  if (customColors[selectedCustomColor]) setColorDraft(customColors[selectedCustomColor]);
  renderCustomColors();
});
byId('add-custom-color').addEventListener('click', () => {
  if (byId('color-confirm').disabled) return;
  let index = selectedCustomColor;
  if (index < 0) index = Array.from({ length: 24 }, (_, slot) => slot).find(slot => !customColors[slot]) ?? 23;
  customColors[index] = colorDraft.hex;
  selectedCustomColor = index;
  renderCustomColors();
  try {
    localStorage.setItem(CUSTOM_COLORS_KEY, JSON.stringify(customColors));
    byId('custom-color-status').textContent = '已保存自定义颜色';
  } catch { byId('custom-color-status').textContent = '当前颜色仅在本次页面中保留'; }
});
byId('color-default').addEventListener('click', () => {
  const item = page.components.find(entry => entry.id === selectedId);
  if (!item) return;
  setColorDraft(componentColorHex(item, true));
  colorDraft.useDefault = true;
});
byId('color-form').addEventListener('submit', (event) => {
  event.preventDefault();
  if (!byId('color-confirm').disabled) applyComponentColor(colorDraft.useDefault ? undefined : colorDraft.hex);
});
byId('color-cancel').addEventListener('click', () => byId('color-palette').close());

function finishInlineEdit(cancelled) {
  if (!inlineEdit) return;
  const editing = inlineEdit;
  inlineEdit = null;
  const value = cancelled ? editing.before : editing.node.textContent.slice(0, editing.field === 'description' ? 500 : 200);
  const hasChange = editing.item[editing.field] !== value || editing.before !== value;
  editing.item[editing.field] = value;
  editing.node.removeAttribute('contenteditable');
  editing.node.removeAttribute('role');
  editing.node.removeAttribute('aria-label');
  editing.node.blur();
  updateComponent(editing.item);
  contentInput.value = editing.item.content;
  descriptionInput.value = editing.item.description || '';
  if (hasChange) changed();
  else savePage();
}

document.addEventListener('pointerdown', (event) => {
  if (inlineEdit && !inlineEdit.node.contains(event.target)) finishInlineEdit(false);
}, { capture: true });
canvas.addEventListener('input', (event) => {
  if (!inlineEdit || event.target !== inlineEdit.node) return;
  const limit = inlineEdit.field === 'description' ? 500 : 200;
  const text = inlineEdit.node.textContent;
  if (text.length > limit && !event.isComposing) {
    inlineEdit.node.textContent = text.slice(0, limit);
    const range = document.createRange();
    range.selectNodeContents(inlineEdit.node);
    range.collapse(false);
    window.getSelection().removeAllRanges();
    window.getSelection().addRange(range);
  }
  inlineEdit.item[inlineEdit.field] = inlineEdit.node.textContent.slice(0, limit);
  contentInput.value = inlineEdit.item.content;
  descriptionInput.value = inlineEdit.item.description || '';
  const row = timelineRows.get(inlineEdit.item.id);
  if (row) row.querySelector('.track-name').textContent = definitions[inlineEdit.item.type].name + ' · ' + inlineEdit.item.content;
  changed(300);
});
canvas.addEventListener('keydown', (event) => {
  if (!inlineEdit || event.isComposing) return;
  if (event.key === 'Escape' || (event.key === 'Enter' && !event.shiftKey)) {
    event.preventDefault();
    event.stopPropagation();
    const node = nodes.get(inlineEdit.item.id);
    finishInlineEdit(event.key === 'Escape');
    node.focus({ preventScroll: true });
  }
});

function playAnimation(item, leaving = false) {
  if (timelineAnimations.length) return;
  const body = nodes.get(item.id).firstElementChild;
  const effect = item.animation.effect;
  const current = getComputedStyle(body);
  const previous = { transform: current.transform, boxShadow: current.boxShadow };
  animations.get(item.id)?.cancel();
  animations.delete(item.id);
  if (effect === 'none' || (leaving && effect === 'rotate')) return;
  const hover = item.animation.trigger === 'hover';
  const baseline = getComputedStyle(body);
  const property = effect === 'shadow' ? 'boxShadow' : 'transform';
  const base = baseline[property];
  const target = effect === 'shadow' ? '0 12px 30px rgba(24, 36, 58, 0.28)' : 'scale(1.08)';
  let frames;
  let duration;
  if (effect === 'rotate') {
    frames = [{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }];
    duration = 700;
  } else if (hover) {
    frames = [{ [property]: previous[property] }, { [property]: leaving ? base : target }];
    duration = 200;
  } else {
    frames = effect === 'shadow'
      ? [{ [property]: base, offset: 0 }, { [property]: target, offset: .25 }, { [property]: target, offset: .75 }, { [property]: base, offset: 1 }]
      : [{ [property]: base, offset: 0 }, { [property]: target, offset: .5 }, { [property]: base, offset: 1 }];
    duration = effect === 'shadow' ? 800 : 400;
  }
  const hold = hover && !leaving && effect !== 'rotate';
  const animation = body.animate(frames.map((frame) => ({ ...frame, easing: 'ease-in-out' })),
    { duration, easing: 'linear', fill: hold ? 'forwards' : 'none' });
  animations.set(item.id, animation);
  animation.onfinish = () => {
    if (!hold && animations.get(item.id) === animation) {
      animation.cancel();
      animations.delete(item.id);
    }
  };
}

function mountComponent(item) {
  const node = document.createElement('div');
  node.className = 'canvas-component';
  node.dataset.id = item.id;
  node.tabIndex = 0;
  node.setAttribute('role', item.type === 'button' ? 'button' : 'group');
  const body = document.createElement('div');
  body.className = 'component-body component-' + item.type;
  if (item.type === 'card') body.append(document.createElement('strong'), document.createElement('p'));
  node.append(body);
  for (const [corner, label] of [['nw', '左上'], ['ne', '右上'], ['sw', '左下'], ['se', '右下']]) {
    const handle = document.createElement('button');
    handle.type = 'button';
    handle.className = 'component-resize';
    handle.dataset.corner = corner;
    handle.setAttribute('aria-label', '拖动' + label + '角调整大小');
    node.append(handle);
  }
  canvas.append(node);
  nodes.set(item.id, node);
  updateComponent(item);
  node.addEventListener('click', () => {
    if (mode === 'edit') selectComponent(item.id);
    else if (item.animation.trigger === 'click') playAnimation(item);
  });
  node.addEventListener('dblclick', (event) => {
    if (mode !== 'edit' || item.type === 'divider' || event.target.closest('.component-resize')) return;
    const target = item.type === 'card'
      ? event.target.closest('strong, p') || body.querySelector('strong')
      : body;
    if (!target) return;
    event.preventDefault();
    finishInlineEdit(false);
    selectComponent(item.id);
    const field = item.type === 'card' && target.tagName === 'P' ? 'description' : 'content';
    inlineEdit = { item, node: target, field, before: item[field] };
    target.contentEditable = 'plaintext-only';
    target.setAttribute('role', 'textbox');
    target.setAttribute('aria-label', field === 'description' ? '编辑卡片描述' : '编辑组件文字');
    target.addEventListener('blur', () => finishInlineEdit(false), { once: true });
    target.focus({ preventScroll: true });
    const range = document.createRange();
    range.selectNodeContents(target);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
  });
  node.addEventListener('keydown', (event) => {
    if (event.target !== node || !['Enter', ' '].includes(event.key)) return;
    event.preventDefault();
    if (mode === 'edit') selectComponent(item.id);
    else if (item.animation.trigger === 'click') playAnimation(item);
  });
  node.addEventListener('pointerenter', () => {
    if (mode === 'preview' && item.animation.trigger === 'hover') playAnimation(item);
  });
  node.addEventListener('pointerleave', () => {
    if (mode === 'preview' && item.animation.trigger === 'hover') playAnimation(item, true);
  });
  node.addEventListener('pointerdown', (event) => {
    if (mode !== 'edit' || drag || !event.isPrimary || event.button !== 0) return;
    if (inlineEdit?.node.contains(event.target)) return;
    event.preventDefault();
    selectComponent(item.id);
    node.focus({ preventScroll: true });
    const bounds = canvas.getBoundingClientRect();
    const scale = bounds.width / canvasWidth;
    drag = { item, node, pointerId: event.pointerId, startX: item.x, startY: item.y,
      startWidth: item.width, startHeight: item.height, clientX: event.clientX, clientY: event.clientY,
      resize: event.target.closest('.component-resize')?.dataset.corner,
      offsetX: (event.clientX - bounds.left) / scale - item.x, offsetY: (event.clientY - bounds.top) / scale - item.y };
    node.setPointerCapture(event.pointerId);
    node.classList.add('dragging');
  });
  node.addEventListener('pointermove', (event) => {
    if (drag?.node !== node || drag.pointerId !== event.pointerId) return;
    if (drag.resize) {
      const scale = canvas.getBoundingClientRect().width / canvasWidth;
      const dx = (event.clientX - drag.clientX) / scale;
      const dy = (event.clientY - drag.clientY) / scale;
      const minHeight = item.type === 'divider' ? 12 : 24;
      if (drag.resize.includes('w')) {
        item.x = Math.round(Math.max(0, Math.min(drag.startX + drag.startWidth - 40, drag.startX + dx)));
        item.width = drag.startX + drag.startWidth - item.x;
      } else item.width = Math.round(Math.max(40, Math.min(canvasWidth - drag.startX, drag.startWidth + dx)));
      if (drag.resize.includes('n')) {
        item.y = Math.round(Math.max(0, Math.min(drag.startY + drag.startHeight - minHeight, drag.startY + dy)));
        item.height = drag.startY + drag.startHeight - item.y;
      } else item.height = Math.round(Math.max(minHeight, Math.min(canvasHeight - drag.startY, drag.startHeight + dy)));
    } else {
      drag.clientX = event.clientX;
      drag.clientY = event.clientY;
      moveDraggedComponent();
      return;
    }
    updateComponent(item);
  });
  node.addEventListener('pointerup', (event) => {
    if (drag?.node === node && drag.pointerId === event.pointerId) finishDrag(false);
  });
  for (const eventName of ['pointercancel', 'lostpointercapture']) {
    node.addEventListener(eventName, (event) => {
      if (drag?.node === node && drag.pointerId === event.pointerId) finishDrag(true);
    });
  }
}

function moveDraggedComponent() {
  if (!drag || drag.resize) return;
  const { item, clientX, clientY, offsetX, offsetY } = drag;
  const bounds = canvas.getBoundingClientRect();
  const scale = bounds.width / canvasWidth;
  item.x = Math.round(Math.max(0, Math.min(canvasWidth - item.width, (clientX - bounds.left) / scale - offsetX)));
  item.y = Math.round(Math.max(0, Math.min(canvasHeight - item.height, (clientY - bounds.top) / scale - offsetY)));
  updateComponent(item);
}

canvas.closest('.canvas-viewport').addEventListener('scroll', moveDraggedComponent, { passive: true });

function finishDrag(cancelled) {
  if (!drag) return;
  const finished = drag;
  drag = null;
  if (cancelled) {
    finished.item.x = finished.startX;
    finished.item.y = finished.startY;
    finished.item.width = finished.startWidth;
    finished.item.height = finished.startHeight;
    updateComponent(finished.item);
  } else if (finished.item.x !== finished.startX || finished.item.y !== finished.startY ||
      finished.item.width !== finished.startWidth || finished.item.height !== finished.startHeight) changed();
  finished.node.classList.remove('dragging');
  if (finished.node.hasPointerCapture(finished.pointerId)) finished.node.releasePointerCapture(finished.pointerId);
  savePage();
}

function updateCanvasSummary() {
  byId('canvas-empty').hidden = page.components.length > 0;
  byId('component-count').textContent = page.components.length + ' 个组件';
  renderTimeline();
}

function addComponent(type, x, y) {
  if (mode !== 'edit' || !Object.hasOwn(definitions, type)) return;
  const definition = definitions[type];
  const item = { id: generateId(), type,
    x: Math.round(Math.max(0, Math.min(canvasWidth - definition.width, x))),
    y: Math.round(Math.max(0, Math.min(canvasHeight - definition.height, y))),
    width: definition.width, height: definition.height,
    content: definition.content, animation: { trigger: 'click', effect: 'none' },
    timeline: { effect: 'none', start: 0, duration: 700 } };
  if (type === 'card') item.description = definition.description;
  page.components.push(item);
  mountComponent(item);
  selectComponent(item.id);
  updateCanvasSummary();
  changed();
}

let libraryDrag = null;
let libraryGhost = null;
function showLibraryGhost(type, clientX, clientY) {
  if (!libraryGhost) {
    libraryGhost = document.createElement('div');
    libraryGhost.className = 'library-ghost';
    const definition = definitions[type];
    const bounds = canvas.getBoundingClientRect();
    const scale = bounds.width / canvasWidth;
    libraryGhost.style.width = definition.width * scale + 'px';
    libraryGhost.style.height = definition.height * scale + 'px';
    libraryGhost.textContent = definition.name;
    document.body.append(libraryGhost);
  }
  libraryGhost.style.left = clientX + 'px';
  libraryGhost.style.top = clientY + 'px';
}
function hideLibraryGhost() {
  if (libraryGhost) {
    libraryGhost.remove();
    libraryGhost = null;
  }
}
for (const button of libraryItems) {
  button.addEventListener('pointerdown', (event) => {
    if (mode !== 'edit' || event.button !== 0) return;
    libraryDrag = { type: button.dataset.type, moved: false, x0: event.clientX, y0: event.clientY };
  });
}
document.addEventListener('pointermove', (event) => {
  if (!libraryDrag) return;
  if (!libraryDrag.moved && Math.hypot(event.clientX - libraryDrag.x0, event.clientY - libraryDrag.y0) < 4) return;
  libraryDrag.moved = true;
  showLibraryGhost(libraryDrag.type, event.clientX, event.clientY);
  const bounds = canvas.getBoundingClientRect();
  const inside = event.clientX >= bounds.left && event.clientX <= bounds.right
    && event.clientY >= bounds.top && event.clientY <= bounds.bottom;
  canvas.classList.toggle('drop-ready', inside);
});
document.addEventListener('pointerup', (event) => {
  if (!libraryDrag) return;
  const drag = libraryDrag;
  libraryDrag = null;
  canvas.classList.remove('drop-ready');
  hideLibraryGhost();
  if (!drag.moved) return;
  const bounds = canvas.getBoundingClientRect();
  const inside = event.clientX >= bounds.left && event.clientX <= bounds.right
    && event.clientY >= bounds.top && event.clientY <= bounds.bottom;
  if (inside) {
    const scale = bounds.width / canvasWidth;
    addComponent(drag.type, (event.clientX - bounds.left) / scale, (event.clientY - bounds.top) / scale);
  }
});
canvas.addEventListener('click', (event) => {
  if (mode === 'edit' && event.target === canvas) selectComponent(null);
});

form.addEventListener('submit', (event) => event.preventDefault());
form.addEventListener('input', (event) => {
  if (mode !== 'edit' || ![contentInput, descriptionInput].includes(event.target)) return;
  const item = page.components.find((entry) => entry.id === selectedId);
  if (!item) return;
  item.content = contentInput.value;
  if (item.type === 'card') item.description = descriptionInput.value;
  updateComponent(item);
  changed(300);
});
form.addEventListener('change', (event) => {
  if (mode !== 'edit') return;
  const item = page.components.find((entry) => entry.id === selectedId);
  if (!item) return;
  if (['component-width', 'component-height'].includes(event.target.id)) {
    const value = Number(event.target.value);
    if (Number.isFinite(value)) {
      if (event.target.id === 'component-width') item.width = Math.round(Math.max(40, Math.min(canvasWidth - item.x, value)));
      else item.height = Math.round(Math.max(item.type === 'divider' ? 12 : 24, Math.min(canvasHeight - item.y, value)));
    }
    updateComponent(item);
    changed();
    return;
  }
  if (![triggerInput, effectInput].includes(event.target)) return;
  item.animation = { trigger: triggerInput.value, effect: effectInput.value };
  updateAnimationDescription();
  changed();
});
function deleteSelectedComponent() {
  if (mode !== 'edit' || !selectedId) return;
  finishInlineEdit(false);
  finishDrag(true);
  animations.get(selectedId)?.cancel();
  animations.delete(selectedId);
  nodes.get(selectedId)?.remove();
  nodes.delete(selectedId);
  page.components = page.components.filter((item) => item.id !== selectedId);
  selectComponent(null);
  updateCanvasSummary();
  changed();
}
deleteButton.addEventListener('click', deleteSelectedComponent);
window.addEventListener('keydown', (event) => {
  if (event.key !== 'Delete') return;
  if (mode !== 'edit' || !selectedId || inlineEdit) return;
  if (event.defaultPrevented || event.isComposing) return;
  const target = event.target;
  if (target instanceof HTMLElement
      && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) return;
  event.preventDefault();
  deleteSelectedComponent();
}, true);

for (const button of modeButtons) {
  button.addEventListener('click', () => {
    finishInlineEdit(false);
    if (byId('color-palette').open) byId('color-palette').close();
    finishDrag(true);
    finishTimelineDrag(true);
    stopTimeline();
    savePage();
    for (const animation of animations.values()) animation.cancel();
    animations.clear();
    canvas.classList.remove('drop-ready');
    libraryDrag = null;
    mode = button.dataset.mode;
    document.body.dataset.mode = mode;
    for (const modeButton of modeButtons) modeButton.setAttribute('aria-pressed', String(modeButton === button));
    for (const item of libraryItems) { item.disabled = mode === 'preview'; }
    byId('inspector-fields').disabled = mode === 'preview';
    deleteButton.disabled = mode === 'preview';
    byId('canvas-size').disabled = mode === 'preview';
    updateTimelineSelection();
    byId('preview-notice').hidden = mode !== 'preview';
    byId('mode-label').textContent = mode === 'preview' ? '预览模式' : '编辑模式';
    byId('canvas-hint').textContent = mode === 'preview'
      ? '点击或悬停组件，体验配置的动画。' : '拖入组件后，点击选中，拖动调整位置。';
  });
}

function fitCanvas() {
  const viewport = canvas.parentElement.parentElement;
  const widthScale = Math.max(.01, (viewport.clientWidth - 32) / canvasWidth);
  const heightScale = Math.max(.01, (viewport.clientHeight - 32) / canvasHeight);
  const scale = Math.max(.05, Math.min(8, zoomMode === 'manual' ? zoomScale : zoomMode === 'width' ? widthScale : Math.min(widthScale, heightScale)));
  canvas.style.width = canvasWidth + 'px';
  canvas.style.height = canvasHeight + 'px';
  canvas.style.transform = 'scale(' + scale + ')';
  canvas.style.setProperty('--resize-handle-size', 8 / scale + 'px');
  canvas.parentElement.style.width = canvasWidth * scale + 'px';
  canvas.parentElement.style.height = canvasHeight * scale + 'px';
  byId('zoom-reset').textContent = Math.round(scale * 100) + '%';
  byId('zoom-out').disabled = scale <= .1;
  byId('zoom-in').disabled = scale >= 8;
  byId('zoom-fit').setAttribute('aria-pressed', String(zoomMode === 'fit'));
  byId('zoom-width').setAttribute('aria-pressed', String(zoomMode === 'width'));
}

function setCanvasZoom(nextMode, scale = 1) {
  const viewport = canvas.parentElement.parentElement;
  const rect = viewport.getBoundingClientRect();
  const before = canvas.getBoundingClientRect();
  const oldScale = before.width / canvasWidth;
  const centerX = (rect.left + viewport.clientWidth / 2 - before.left) / oldScale;
  const centerY = (rect.top + viewport.clientHeight / 2 - before.top) / oldScale;
  zoomMode = nextMode;
  zoomScale = Math.max(.1, Math.min(8, scale));
  fitCanvas();
  if (nextMode === 'manual') {
    const after = canvas.getBoundingClientRect();
    const newScale = after.width / canvasWidth;
    viewport.scrollLeft += after.left + centerX * newScale - (rect.left + viewport.clientWidth / 2);
    viewport.scrollTop += after.top + centerY * newScale - (rect.top + viewport.clientHeight / 2);
  } else { viewport.scrollLeft = 0; viewport.scrollTop = 0; }
}

byId('zoom-out').addEventListener('click', () => setCanvasZoom('manual', canvas.getBoundingClientRect().width / canvasWidth - .1));
byId('zoom-in').addEventListener('click', () => setCanvasZoom('manual', canvas.getBoundingClientRect().width / canvasWidth + .1));
byId('zoom-reset').addEventListener('click', () => setCanvasZoom('manual', 1));
byId('zoom-fit').addEventListener('click', () => setCanvasZoom('fit'));
byId('zoom-width').addEventListener('click', () => setCanvasZoom('width'));

const panelAxes = {
  vertical: { prop: '--library-width', fallbackVar: '--library-default', minVar: '--library-min', otherVar: '--canvas-min', client: 'clientWidth', pointer: 'clientX', target: 'component-library', label: '组件库宽度', subtract: 24, adjustments: { ArrowLeft: 24, ArrowRight: -24 } },
  horizontal: { prop: '--inspector-height', fallback: 270, minVar: '--inspector-min', otherVar: '--upper-min', client: 'clientHeight', pointer: 'clientY', target: 'component-inspector', label: '底部面板高度', subtract: 12, adjustments: { ArrowUp: 24, ArrowDown: -24 } },
  chat: { prop: '--chat-width', fallbackVar: '--chat-default', minVar: '--chat-min', otherVar: '--canvas-min', client: 'clientWidth', pointer: 'clientX', target: 'chat-panel', label: '对话框宽度', subtract: 24, adjustments: { ArrowLeft: 24, ArrowRight: -24 } }
};

function resizePanels(axis, value) {
  const spec = panelAxes[axis];
  const styles = getComputedStyle(workbench);
  const available = Math.max(0, workbench[spec.client] - spec.subtract);
  const min = Math.min(parseFloat(styles.getPropertyValue(spec.minVar)), available * .45);
  const otherMin = Math.min(parseFloat(styles.getPropertyValue(spec.otherVar)), available * .45);
  const max = Math.max(min, available - otherMin);
  const actual = Math.max(min, Math.min(max, value));
  workbench.style.setProperty(spec.prop, actual + 'px');
  const handle = byId(axis + '-splitter');
  handle.setAttribute('aria-valuemin', Math.round(min));
  handle.setAttribute('aria-valuemax', Math.round(max));
  handle.setAttribute('aria-valuenow', Math.round(actual));
  handle.setAttribute('aria-valuetext', spec.label + ' ' + Math.round(actual) + ' 像素');
}

function finishPanelResize(cancelled) {
  if (!panelResize) return;
  const finished = panelResize;
  panelResize = null;
  if (cancelled) resizePanels(finished.axis, finished.initial);
  finished.handle.classList.remove('resizing');
  workbench.classList.remove('resizing');
  if (finished.handle.hasPointerCapture(finished.pointerId)) finished.handle.releasePointerCapture(finished.pointerId);
}

for (const axis of Object.keys(panelAxes)) {
  const handle = byId(axis + '-splitter');
  const spec = panelAxes[axis];
  const widthBased = spec.client === 'clientWidth';
  handle.addEventListener('pointerdown', (event) => {
    if (panelResize || event.button !== 0 || !event.isPrimary) return;
    event.preventDefault();
    const target = byId(spec.target).getBoundingClientRect();
    panelResize = { axis, handle, pointerId: event.pointerId, start: event[spec.pointer],
      initial: widthBased ? target.width : target.height };
    handle.focus({ preventScroll: true });
    handle.setPointerCapture(event.pointerId);
    handle.classList.add('resizing');
    workbench.classList.add('resizing');
  });
  handle.addEventListener('pointermove', (event) => {
    if (panelResize?.handle !== handle || panelResize.pointerId !== event.pointerId) return;
    resizePanels(axis, panelResize.initial - (event[spec.pointer] - panelResize.start));
  });
  handle.addEventListener('pointerup', (event) => {
    if (panelResize?.handle === handle && panelResize.pointerId === event.pointerId) finishPanelResize(false);
  });
  for (const eventName of ['pointercancel', 'lostpointercapture']) handle.addEventListener(eventName, (event) => {
    if (panelResize?.handle === handle && panelResize.pointerId === event.pointerId) finishPanelResize(true);
  });
  handle.addEventListener('keydown', (event) => {
    const target = byId(spec.target).getBoundingClientRect();
    const current = widthBased ? target.width : target.height;
    const adjustments = spec.adjustments;
    if (!Object.hasOwn(adjustments, event.key) && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    resizePanels(axis, event.key === 'Home' ? 0 : event.key === 'End' ? Infinity : current + adjustments[event.key]);
  });
}

new ResizeObserver(() => {
  const styles = getComputedStyle(workbench);
  for (const axis of Object.keys(panelAxes)) {
    const spec = panelAxes[axis];
    const fallback = spec.fallbackVar ? styles.getPropertyValue(spec.fallbackVar) : spec.fallback;
    resizePanels(axis, parseFloat(styles.getPropertyValue(spec.prop) || fallback));
  }
}).observe(workbench);

function updateTimelineSelection() {
  const item = page.components.find((entry) => entry.id === selectedId);
  byId('timeline-fields').disabled = !item || mode !== 'edit';
  for (const [id, row] of timelineRows) row.classList.toggle('selected', id === selectedId);
  if (!item) return;
  byId('timeline-effect').value = item.timeline.effect;
  byId('timeline-start').value = (item.timeline.start / 1000).toFixed(1);
  byId('timeline-duration').value = (item.timeline.duration / 1000).toFixed(1);
}

function updateTimelineBlock(item) {
  const row = timelineRows.get(item.id);
  const block = row.querySelector('.timeline-block');
  block.hidden = item.timeline.effect === 'none';
  row.querySelector('.add-animation').hidden = item.timeline.effect !== 'none';
  block.style.left = item.timeline.start / TIMELINE_DURATION * 100 + '%';
  block.style.width = item.timeline.duration / TIMELINE_DURATION * 100 + '%';
  block.firstElementChild.textContent = effectNames[item.timeline.effect];
  block.setAttribute('aria-label', effectNames[item.timeline.effect] + '，开始 ' + item.timeline.start / 1000 + ' 秒，时长 ' + item.timeline.duration / 1000 + ' 秒');
}

function renderTimeline() {
  const tracks = byId('timeline-tracks');
  tracks.replaceChildren();
  timelineRows.clear();
  for (const item of page.components) {
    const row = document.createElement('div');
    row.className = 'timeline-track';
    row.dataset.id = item.id;
    const name = document.createElement('button');
    name.type = 'button';
    name.className = 'track-name';
    name.textContent = definitions[item.type].name + (item.content ? ' · ' + item.content : '');
    name.title = name.textContent;
    name.addEventListener('click', () => selectComponent(item.id));
    const rail = document.createElement('div');
    rail.className = 'track-rail';
    const block = document.createElement('div');
    block.className = 'timeline-block';
    block.tabIndex = 0;
    block.setAttribute('role', 'button');
    const caption = document.createElement('span');
    const resize = document.createElement('span');
    resize.className = 'timeline-resize';
    resize.title = '拖动调整时长';
    block.append(caption, resize);
    block.addEventListener('click', () => selectComponent(item.id));
    block.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); selectComponent(item.id); }
    });
    block.addEventListener('pointerdown', (event) => {
      if (mode !== 'edit' || timelineDrag || event.button !== 0 || !event.isPrimary) return;
      event.preventDefault();
      selectComponent(item.id);
      timelineDrag = { item, block, pointerId: event.pointerId, x: event.clientX, width: rail.getBoundingClientRect().width,
        resize: event.target === resize, start: item.timeline.start, duration: item.timeline.duration };
      block.setPointerCapture(event.pointerId);
    });
    block.addEventListener('pointermove', (event) => {
      if (timelineDrag?.block !== block || timelineDrag.pointerId !== event.pointerId) return;
      const offset = Math.round((event.clientX - timelineDrag.x) / timelineDrag.width * TIMELINE_DURATION / 100) * 100;
      if (timelineDrag.resize) item.timeline.duration = Math.max(100, Math.min(TIMELINE_DURATION - item.timeline.start, timelineDrag.duration + offset));
      else item.timeline.start = Math.max(0, Math.min(TIMELINE_DURATION - item.timeline.duration, timelineDrag.start + offset));
      updateTimelineBlock(item);
      updateTimelineSelection();
    });
    block.addEventListener('pointerup', (event) => {
      if (timelineDrag?.block === block && timelineDrag.pointerId === event.pointerId) finishTimelineDrag(false);
    });
    for (const eventName of ['pointercancel', 'lostpointercapture']) block.addEventListener(eventName, (event) => {
      if (timelineDrag?.block === block && timelineDrag.pointerId === event.pointerId) finishTimelineDrag(true);
    });
    const add = document.createElement('button');
    add.type = 'button';
    add.className = 'add-animation';
    add.textContent = '＋ 添加动画';
    add.addEventListener('click', () => {
      if (mode !== 'edit') return;
      item.timeline.effect = 'rotate';
      selectComponent(item.id);
      updateTimelineBlock(item);
      changed();
    });
    rail.append(block, add);
    row.append(name, rail);
    tracks.append(row);
    timelineRows.set(item.id, row);
    updateTimelineBlock(item);
  }
  byId('timeline-empty').hidden = page.components.length > 0;
  byId('timeline-play').disabled = page.components.length === 0;
  updateTimelineSelection();
}

function finishTimelineDrag(cancelled) {
  if (!timelineDrag) return;
  const finished = timelineDrag;
  timelineDrag = null;
  if (cancelled) {
    finished.item.timeline.start = finished.start;
    finished.item.timeline.duration = finished.duration;
    updateTimelineBlock(finished.item);
    updateTimelineSelection();
  } else changed();
  if (finished.block.hasPointerCapture(finished.pointerId)) finished.block.releasePointerCapture(finished.pointerId);
  savePage();
}

function setTimelineTime(time) {
  timelineTime = Math.max(0, Math.min(TIMELINE_DURATION, time));
  for (const animation of timelineAnimations) animation.currentTime = timelineTime;
  byId('timeline-panel').style.setProperty('--playhead', timelineTime / TIMELINE_DURATION * 100 + '%');
  byId('timeline-clock').value = (timelineTime / 1000).toFixed(1) + ' / 10.0s';
}

function stopTimeline() {
  cancelAnimationFrame(timelineFrame);
  timelinePlaying = false;
  for (const animation of timelineAnimations) animation.cancel();
  timelineAnimations.length = 0;
  setTimelineTime(0);
  byId('timeline-play').textContent = '▷ 播放';
}

function prepareTimeline() {
  for (const animation of animations.values()) animation.cancel();
  animations.clear();
  for (const animation of timelineAnimations) animation.cancel();
  timelineAnimations.length = 0;
  for (const item of page.components) {
    if (item.timeline.effect === 'none') continue;
    const body = nodes.get(item.id).firstElementChild;
    const style = getComputedStyle(body);
    let frames;
    if (item.timeline.effect === 'shadow') frames = [
      { boxShadow: style.boxShadow, offset: 0 }, { boxShadow: '0 12px 30px rgba(24, 36, 58, 0.28)', offset: .25 },
      { boxShadow: '0 12px 30px rgba(24, 36, 58, 0.28)', offset: .75 }, { boxShadow: style.boxShadow, offset: 1 }
    ];
    else if (item.timeline.effect === 'scale') frames = [
      { transform: 'none', offset: 0 }, { transform: 'scale(1.08)', offset: .5 }, { transform: 'none', offset: 1 }
    ];
    else frames = [{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }];
    const animation = body.animate(frames.map(frame => ({ ...frame, easing: 'ease-in-out' })),
      { duration: item.timeline.duration, delay: item.timeline.start, fill: 'both' });
    animation.pause();
    timelineAnimations.push(animation);
  }
  setTimelineTime(timelineTime);
}

function tickTimeline(now) {
  setTimelineTime(now - timelineStart);
  if (timelineTime < TIMELINE_DURATION) timelineFrame = requestAnimationFrame(tickTimeline);
  else { timelinePlaying = false; byId('timeline-play').textContent = '▷ 播放'; }
}

byId('canvas-size').addEventListener('change', (event) => {
  if (mode !== 'edit') return;
  finishDrag(true);
  [canvasWidth, canvasHeight] = canvasSizes[event.target.value];
  page.canvasSize = event.target.value;
  for (const item of page.components) {
    item.width = Math.min(item.width, canvasWidth);
    item.height = Math.min(item.height, canvasHeight);
    item.x = Math.min(item.x, canvasWidth - item.width);
    item.y = Math.min(item.y, canvasHeight - item.height);
    updateComponent(item);
  }
  fitCanvas();
  changed();
});

for (const button of document.querySelectorAll('.bottom-tabs button')) button.addEventListener('click', () => {
  byId('timeline-panel').hidden = button.dataset.panel !== 'timeline';
  byId('properties-panel').hidden = button.dataset.panel !== 'properties';
  for (const tab of document.querySelectorAll('.bottom-tabs button')) tab.setAttribute('aria-pressed', String(tab === button));
  fitCanvas();
});

byId('timeline-fields').addEventListener('change', (event) => {
  if (mode !== 'edit') return;
  const item = page.components.find(entry => entry.id === selectedId);
  if (!item) return;
  if (event.target.id === 'timeline-effect') item.timeline.effect = event.target.value;
  else {
    const value = Number(event.target.value);
    if (!Number.isFinite(value)) { updateTimelineSelection(); return; }
    const ms = Math.round(value * 10) * 100;
    if (event.target.id === 'timeline-start') item.timeline.start = Math.max(0, Math.min(TIMELINE_DURATION - item.timeline.duration, ms));
    else item.timeline.duration = Math.max(100, Math.min(TIMELINE_DURATION - item.timeline.start, ms));
  }
  updateTimelineBlock(item);
  updateTimelineSelection();
  changed();
});

byId('timeline-play').addEventListener('click', () => {
  if (timelinePlaying) {
    cancelAnimationFrame(timelineFrame);
    timelinePlaying = false;
    byId('timeline-play').textContent = '▷ 播放';
    return;
  }
  if (mode === 'edit') document.querySelector('.mode-switch [data-mode="preview"]').click();
  if (timelineTime >= TIMELINE_DURATION) setTimelineTime(0);
  prepareTimeline();
  timelinePlaying = true;
  timelineStart = performance.now() - timelineTime;
  byId('timeline-play').textContent = 'Ⅱ 暂停';
  timelineFrame = requestAnimationFrame(tickTimeline);
});
byId('timeline-stop').addEventListener('click', stopTimeline);
byId('timeline-ruler').addEventListener('click', (event) => {
  const rect = event.currentTarget.getBoundingClientRect();
  const time = (event.clientX - rect.left) / rect.width * TIMELINE_DURATION;
  if (mode === 'edit') document.querySelector('.mode-switch [data-mode="preview"]').click();
  cancelAnimationFrame(timelineFrame);
  timelinePlaying = false;
  byId('timeline-play').textContent = '▷ 播放';
  prepareTimeline();
  setTimelineTime(time);
});

window.addEventListener('blur', () => { finishDrag(true); finishTimelineDrag(true); finishPanelResize(true); });
window.addEventListener('pagehide', () => { finishInlineEdit(false); finishDrag(true); finishTimelineDrag(true); finishPanelResize(true); savePage(); });
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') { finishDrag(true); finishTimelineDrag(true); finishPanelResize(true); savePage(); }
});

let canvasId = null;
let agentBusy = false;

async function resolveCanvasId() {
  const fromUrl = new URL(location.href).searchParams.get('canvas');
  if (fromUrl) {
    try { localStorage.setItem(CANVAS_KEY, fromUrl); } catch { /* 忽略 */ }
    return fromUrl;
  }
  let stored = null;
  try { stored = localStorage.getItem(CANVAS_KEY); } catch { /* 忽略 */ }
  if (stored) return stored;
  const response = await fetch('/api/canvases', { method: 'POST' });
  if (!response.ok) throw new Error('无法创建画布');
  const body = await response.json();
  localStorage.setItem(CANVAS_KEY, body.id);
  return body.id;
}

function setBusy(flag) {
  agentBusy = flag;
  window.__editorApi.busy = flag;
  document.body.dataset.agentBusy = String(flag);
  if (flag) {
    finishInlineEdit(false);
    finishDrag(true);
    finishTimelineDrag(true);
  }
  for (const item of libraryItems) {
    item.disabled = mode === 'preview';
    item.disabled = mode === 'preview';
  }
}

function editorReady() {
  window.__editorApi.ready = true;
  window.__editorApi.canvasId = canvasId;
}

window.__editorApi = { ready: false, busy: false, canvasId: null, setBusy, reloadFromServer };

const pickerButton = byId('canvas-picker-button');
const pickerMenu = byId('canvas-picker-menu');
const pickerList = byId('canvas-picker-list');
const pickerCurrent = byId('canvas-picker-current');
const pickerNew = byId('canvas-picker-new');

function pickerLabel(info) {
  return { name: '画布 ' + info.number, time: formatTime(info.updatedAt) };
}

function formatTime(iso) {
  const date = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' '
      + pad(date.getHours()) + ':' + pad(date.getMinutes());
}

async function refreshCanvasPicker() {
  try {
    const response = await fetch('/api/canvases');
    if (!response.ok) return;
    const canvases = await response.json();
    pickerList.replaceChildren();
    for (const [index, info] of canvases.entries()) {
      const label = pickerLabel(info);
      const item = document.createElement('button');
      item.type = 'button';
      item.className = 'canvas-picker-item';
      if (info.id === canvasId) item.classList.add('current');
      const dot = document.createElement('span');
      dot.className = 'picker-dot';
      dot.setAttribute('aria-hidden', 'true');
      const name = document.createElement('span');
      name.className = 'picker-name';
      name.textContent = label.name;
      const time = document.createElement('span');
      time.className = 'picker-time';
      time.textContent = label.time;
      const remove = document.createElement('span');
      remove.className = 'picker-delete';
      remove.setAttribute('role', 'button');
      remove.setAttribute('tabindex', '0');
      remove.setAttribute('aria-label', '删除' + label.name);
      remove.title = '删除画布及聊天记录';
      remove.textContent = '×';
      const removeCanvas = async (event) => {
        event.preventDefault();
        event.stopPropagation();
        if (remove.dataset.busy === 'true') return;
        if (!confirm('删除' + label.name + '及对应的聊天记录？此操作不可恢复。')) return;
        remove.dataset.busy = 'true';
        remove.style.pointerEvents = 'none';
        try {
          const response = await fetch('/api/canvases/' + info.id, { method: 'DELETE' });
          if (!response.ok) throw new Error('delete failed');
          if (info.id === canvasId) {
            try { localStorage.removeItem(CANVAS_KEY); } catch { /* 忽略 */ }
            location.href = '/';
          } else {
            pickerMenu.hidden = true;
            pickerButton.setAttribute('aria-expanded', 'false');
            refreshCanvasPicker();
          }
        } catch {
          remove.dataset.busy = 'false';
          remove.style.pointerEvents = '';
          alert('删除失败，请重试。');
        }
      };
      remove.addEventListener('click', removeCanvas);
      remove.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') removeCanvas(event);
      });
      item.append(dot, name, time, remove);
      item.addEventListener('click', () => {
        if (info.id !== canvasId) location.href = '/?canvas=' + info.id;
      });
      pickerList.append(item);
    }
    if (canvases.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'picker-time';
      empty.style.padding = '10px 12px';
      empty.textContent = '还没有画布';
      pickerList.append(empty);
    }
    const current = canvases.find((c) => c.id === canvasId);
    if (current) {
      const label = pickerLabel(current);
      pickerCurrent.textContent = label.name;
    } else {
      pickerCurrent.textContent = '画布';
    }
  } catch {
    pickerCurrent.textContent = '画布';
  }
}

pickerButton.addEventListener('click', (event) => {
  event.stopPropagation();
  const open = pickerMenu.hidden;
  pickerMenu.hidden = !open;
  pickerButton.setAttribute('aria-expanded', String(open));
  if (open) refreshCanvasPicker();
});

document.addEventListener('click', (event) => {
  if (!pickerMenu.hidden && !document.getElementById('canvas-picker').contains(event.target)) {
    pickerMenu.hidden = true;
    pickerButton.setAttribute('aria-expanded', 'false');
  }
});

pickerNew.addEventListener('click', async () => {
  pickerNew.disabled = true;
  try {
    const response = await fetch('/api/canvases', { method: 'POST' });
    if (!response.ok) throw new Error('create failed');
    const body = await response.json();
    location.href = '/?canvas=' + body.id;
  } catch {
    pickerNew.disabled = false;
    saveStatus.textContent = '新建画布失败，请重试';
    saveStatus.dataset.state = 'error';
  }
});

const FOLD_KEY = 'hb.component-studio.folds';
const foldButtons = document.querySelectorAll('.panel-fold');
const foldSizes = {};
let folds = {};
try { folds = JSON.parse(localStorage.getItem(FOLD_KEY) || '{}'); } catch { /* 忽略 */ }
for (const [name, collapsed] of Object.entries(folds)) {
  document.body.setAttribute('data-fold-' + name, String(collapsed));
  const button = document.querySelector('.panel-fold[data-fold="' + name + '"]');
  if (button) button.setAttribute('aria-expanded', String(!collapsed));
}
for (const button of foldButtons) {
  button.addEventListener('click', () => {
    const name = button.dataset.fold;
    const collapsed = document.body.getAttribute('data-fold-' + name) === 'true';
    const property = { library: '--library-width', chat: '--chat-width', inspector: '--inspector-height' }[name];
    if (!collapsed && property) {
      foldSizes[name] = workbench.style.getPropertyValue(property);
    }
    document.body.setAttribute('data-fold-' + name, String(!collapsed));
    button.setAttribute('aria-expanded', String(collapsed));
    folds[name] = !collapsed;
    try { localStorage.setItem(FOLD_KEY, JSON.stringify(folds)); } catch { /* 忽略 */ }
    if (collapsed && property && foldSizes[name]) {
      requestAnimationFrame(() => workbench.style.setProperty(property, foldSizes[name]));
    }
    requestAnimationFrame(fitCanvas);
  });
}

(async () => {
  try {
    canvasId = await resolveCanvasId();
    await loadFromServer();
    for (const item of page.components) mountComponent(item);
    updateCanvasSummary();
    byId('canvas-size').value = page.canvasSize;
    editorReady();
  } catch (error) {
    saveStatus.textContent = '画布加载失败：' + (error?.message || error);
    saveStatus.dataset.state = 'error';
  }
  refreshCanvasPicker();
  new ResizeObserver(fitCanvas).observe(canvas.parentElement.parentElement);
  fitCanvas();
})();
