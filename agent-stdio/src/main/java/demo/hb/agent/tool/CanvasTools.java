package demo.hb.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Locale;

/**
 * 画布操作工具集：7 个 {@link Tool} 注解方法，注册时由 Toolkit 自动扫描并生成 schema。
 * 每个工具对应一个画布操作，模型只选工具、填参数，不写任何脚本代码；
 * 执行链路全部由 {@link CanvasToolSupport} 共享处理。
 */
public final class CanvasTools {

    private static final int DEFAULT_TIMEOUT_MS = 180_000;

    private final CanvasToolSupport support;

    public CanvasTools(CanvasToolSupport support) {
        this.support = support;
    }

    @Tool(description =
            "读取画布当前状态：画布尺寸、缩放比例、编辑模式、面板折叠状态、全部组件列表"
                    + "（data-id、aria-label 文字、x/y/width/height 数据坐标）。任何操作前先用它了解现状。"
                    + "只读，不改变页面。")
    public ToolResultBlock read_canvas(RuntimeContext context) {
        return support.run(canvasId(context), """
                const __comps = await page.evaluate(() => {
                    const canvas = document.getElementById('canvas');
                    const cr = canvas.getBoundingClientRect();
                    return {
                        canvasSize: canvas.style.width + 'x' + canvas.style.height,
                        scale: Math.round(cr.width / parseFloat(canvas.style.width) * 1000) / 1000,
                        mode: document.body.dataset.mode || 'edit',
                        components: [...document.querySelectorAll('.canvas-component')].map(n => ({
                            id: n.dataset.id,
                            label: n.getAttribute('aria-label'),
                            x: parseFloat(n.style.left),
                            y: parseFloat(n.style.top),
                            width: parseFloat(n.style.width),
                            height: parseFloat(n.style.height)
                        }))
                    };
                });
                const __compact = { canvasSize: __comps.canvasSize, scale: __comps.scale, mode: __comps.mode,
                    components: __comps.components.map(c => c.id + ' ' + c.label + ' @' + c.x + ',' + c.y + ' ' + c.width + 'x' + c.height) };
                console.log('__HB_RESULT__ ' + JSON.stringify(__compact));
                """, DEFAULT_TIMEOUT_MS, false);
    }

    @Tool(description =
            "为当前画布创建全新的任务空间并重新打开画布页面（丢弃旧的空间/页面绑定，组件数据不变）。"
                    + "当任务空间被结束或清理、页面状态混乱、需要干净环境重来时使用。")
    public ToolResultBlock create_canvas_space(RuntimeContext context) {
        String canvasId = canvasId(context);
        support.invalidate(canvasId);
        return support.run(canvasId, """
                console.log('__HB_RESULT__ ' + JSON.stringify({ spaceRecreated: true }));
                """, DEFAULT_TIMEOUT_MS);
    }

    @Tool(description =
            "从组件库拖入一个新组件到画布数据坐标 (x, y)，返回新组件的 data-id。"
                    + "type 取 button / text / card / heading / tag / divider。"
                    + "拖入后自动验证新增数量与落点坐标（失败或新增多个会报错）。")
    public ToolResultBlock drag_component(RuntimeContext context,
            @ToolParam(name = "type", description = "组件类型：button / text / card / heading / tag / divider") String type,
            @ToolParam(name = "x", description = "目标数据坐标 x（画布 1920 坐标系）") double x,
            @ToolParam(name = "y", description = "目标数据坐标 y") double y) {
        if (!List.of("button", "text", "card", "heading", "tag", "divider").contains(type)) {
            return ToolResultBlock.error("无效组件类型: " + type);
        }
        return support.run(canvasId(context), """
                async function __op(type, dataX, dataY) {
                    const before = await page.evaluate(() =>
                        [...document.querySelectorAll('.canvas-component')].map(n => n.dataset.id));
                    const lib = await page.evaluate((t) => {
                        const el = document.querySelector('.library-item[data-type="' + t + '"]');
                        if (!el) return null;
                        el.scrollIntoView({ block: 'center', inline: 'nearest' });
                        const r = el.getBoundingClientRect();
                        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
                    }, type);
                    if (!lib) throw new Error('组件库没有类型为 ' + type + ' 的组件');
                    const point = await page.evaluate(({ dataX, dataY }) => {
                        const c = document.getElementById('canvas');
                        const viewport = c.closest('.canvas-viewport');
                        const r = c.getBoundingClientRect();
                        const scale = r.width / parseFloat(c.style.width);
                        const offsetX = Math.max(0.25, dataX) * scale;
                        const offsetY = Math.max(0.25, dataY) * scale;
                        const view = viewport.getBoundingClientRect();
                        const x = r.left + offsetX;
                        const y = r.top + offsetY;
                        if (x < view.left || x >= view.left + viewport.clientWidth
                                || y < view.top || y >= view.top + viewport.clientHeight) {
                            viewport.scrollBy(x - view.left - viewport.clientWidth / 2,
                                    y - view.top - viewport.clientHeight / 2);
                        }
                        const visible = c.getBoundingClientRect();
                        return { x: visible.left + offsetX, y: visible.top + offsetY };
                    }, { dataX, dataY });
                    try {
                        await page.mouse.move(lib.x, lib.y);
                        await page.mouse.down();
                        try {
                            for (const progress of [0.05, 0.30, 0.55, 0.80, 1]) {
                                await page.mouse.move(lib.x + (point.x - lib.x) * progress,
                                        lib.y + (point.y - lib.y) * progress);
                                if (progress < 1) await page.waitForTimeout(40);
                            }
                        } finally {
                            await page.mouse.up();
                        }
                        const after = await page.evaluate(() =>
                            [...document.querySelectorAll('.canvas-component')].map(n => n.dataset.id));
                        const added = after.filter(id => !before.includes(id));
                        if (added.length === 0) throw new Error('拖入失败：组件数量没有增加');
                        if (added.length > 1) throw new Error('拖入异常：新增了 ' + added.length + ' 个组件');
                        return added[0];
                    } catch (e) {
                        const __pr = await __probe();
                        if (__pr.down < 1 || __pr.move < 2 || __pr.up < 1) {
                            await page.reload();
                            await page.waitForSelector('.canvas-component, #canvas', { timeout: 15000 });
                            throw new Error('页面指针事件管线异常，已重载恢复，请重试本操作。原始错误：' + e.message);
                        }
                        throw e;
                    }
                }
                const __id = await __op(%s, %s, %s);
                console.log('__HB_RESULT__ ' + JSON.stringify({ componentId: __id }));
                """.formatted(quote(type), x, y), DEFAULT_TIMEOUT_MS);
    }

    @Tool(description =
            "用真实鼠标拖拽把组件移动到画布数据坐标 (x, y)（组件左上角落点），"
                    + "完成后自动读回坐标验证（偏差 >5 报错）。移动失败时不要删除重建组件。")
    public ToolResultBlock move_component(RuntimeContext context,
            @ToolParam(name = "componentId", description = "组件 data-id") String componentId,
            @ToolParam(name = "x", description = "目标数据坐标 x") double x,
            @ToolParam(name = "y", description = "目标数据坐标 y") double y) {
        return support.run(canvasId(context), """
                async function __op(componentId, targetX, targetY) {
                    const points = await page.evaluate(({ cid, targetX, targetY }) => {
                        const el = document.querySelector('.canvas-component[data-id="' + cid + '"]');
                        if (!el) throw new Error('组件不存在: ' + cid);
                        const canvas = el.closest('#canvas');
                        const viewport = canvas.closest('.canvas-viewport');
                        el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
                        const rect = el.getBoundingClientRect();
                        const view = viewport.getBoundingClientRect();
                        const viewLeft = Math.max(view.left + viewport.clientLeft, 0);
                        const viewTop = Math.max(view.top + viewport.clientTop, 0);
                        const viewRight = Math.min(view.left + viewport.clientLeft + viewport.clientWidth, innerWidth);
                        const viewBottom = Math.min(view.top + viewport.clientTop + viewport.clientHeight, innerHeight);
                        const left = Math.max(rect.left, viewLeft);
                        const top = Math.max(rect.top, viewTop);
                        const right = Math.min(rect.right, viewRight);
                        const bottom = Math.min(rect.bottom, viewBottom);
                        if (left >= right || top >= bottom) throw new Error('组件不在画布可视区域: ' + cid);
                        const samples = [0.5, 0.125, 0.875];
                        const from = samples.flatMap(row => samples.map(column => ({
                            x: left + (right - left) * column,
                            y: top + (bottom - top) * row
                        }))).find(({ x, y }) => {
                            const hit = document.elementFromPoint(x, y);
                            return hit?.closest('.canvas-component') === el && !hit.closest('.component-resize');
                        });
                        if (!from) throw new Error('拖拽起点未命中组件正文，可能被遮挡: ' + cid);
                        const canvasBounds = canvas.getBoundingClientRect();
                        const width = parseFloat(canvas.style.width);
                        const height = parseFloat(canvas.style.height);
                        if (!(targetX >= 0 && targetX <= width - parseFloat(el.style.width)
                                && targetY >= 0 && targetY <= height - parseFloat(el.style.height))) {
                            throw new Error('目标坐标会让组件超出画布: ' + cid);
                        }
                        const scale = canvasBounds.width / width;
                        const toX = from.x + (targetX - parseFloat(el.style.left)) * scale;
                        const toY = from.y + (targetY - parseFloat(el.style.top)) * scale;
                        const edgeX = toX < viewLeft ? viewLeft + 8 : toX >= viewRight ? viewRight - 8 : toX;
                        const edgeY = toY < viewTop ? viewTop + 8 : toY >= viewBottom ? viewBottom - 8 : toY;
                        return { fromX: from.x, fromY: from.y, toX, toY, edgeX, edgeY,
                                 canvasLeft: canvasBounds.left, canvasTop: canvasBounds.top };
                    }, { cid: componentId, targetX, targetY });
                    try {
                        await page.mouse.move(points.fromX, points.fromY);
                        await page.mouse.down();
                        try {
                            let drop = { x: points.toX, y: points.toY };
                            if (points.toX !== points.edgeX || points.toY !== points.edgeY) {
                                await page.mouse.move(points.edgeX, points.edgeY, { steps: 3 });
                                drop = await page.evaluate(({ toX, toY, edgeX, edgeY, canvasLeft, canvasTop }) => {
                                    const canvas = document.getElementById('canvas');
                                    canvas.closest('.canvas-viewport').scrollBy({
                                        left: toX - edgeX, top: toY - edgeY, behavior: 'instant'
                                    });
                                    const bounds = canvas.getBoundingClientRect();
                                    return { x: toX + bounds.left - canvasLeft, y: toY + bounds.top - canvasTop };
                                }, points);
                            }
                            await page.mouse.move(drop.x, drop.y, { steps: 3 });
                        } finally {
                            await page.mouse.up();
                        }
                    } catch (e) {
                        const __pr = await __probe();
                        if (__pr.down < 1 || __pr.move < 2 || __pr.up < 1) {
                            await page.reload();
                            await page.waitForSelector('.canvas-component, #canvas', { timeout: 15000 });
                            throw new Error('页面指针事件管线异常，已重载恢复，请重试本操作。原始错误：' + e.message);
                        }
                        throw e;
                    }
                    const after = await page.evaluate((cid) => {
                        const el = document.querySelector('.canvas-component[data-id="' + cid + '"]');
                        return el ? { left: parseFloat(el.style.left), top: parseFloat(el.style.top) } : null;
                    }, componentId);
                    if (!after) throw new Error('移动后组件不存在: ' + componentId);
                    if (Math.abs(after.left - targetX) > 5 || Math.abs(after.top - targetY) > 5) {
                        throw new Error('组件未到达目标: ' + componentId + '，目标 (' + targetX + ', ' + targetY
                                + ')，实际 (' + after.left + ', ' + after.top + ')');
                    }
                    return after;
                }
                const __r = await __op(%s, %s, %s);
                console.log('__HB_RESULT__ ' + JSON.stringify({ moved: __r }));
                """.formatted(quote(componentId), x, y), DEFAULT_TIMEOUT_MS);
    }

    @Tool(description =
            "通过属性面板的宽高输入框调整组件尺寸为 (width, height) 像素，完成后验证。"
                    + "不要拖缩放手柄（会导致页面拖拽状态卡死）。")
    public ToolResultBlock resize_component(RuntimeContext context,
            @ToolParam(name = "componentId", description = "组件 data-id") String componentId,
            @ToolParam(name = "width", description = "目标宽度 px") double width,
            @ToolParam(name = "height", description = "目标高度 px") double height) {
        return support.run(canvasId(context), """
                async function __op(componentId, w, h) {
                    await page.click('.canvas-component[data-id="' + componentId + '"]', { force: true });
                    await page.waitForSelector('#component-width:not([hidden])', { timeout: 3000 });
                    await page.fill('#component-width', String(w));
                    await page.fill('#component-height', String(h));
                    await page.click('#canvas', { position: { x: 5, y: 5 }, force: true });
                    const after = await page.evaluate((cid) => {
                        const el = document.querySelector('.canvas-component[data-id="' + cid + '"]');
                        return el ? { width: parseFloat(el.style.width), height: parseFloat(el.style.height) } : null;
                    }, componentId);
                    if (!after) throw new Error('组件不存在: ' + componentId);
                    return after;
                }
                const __r = await __op(%s, %s, %s);
                console.log('__HB_RESULT__ ' + JSON.stringify({ resized: __r }));
                """.formatted(quote(componentId), width, height), DEFAULT_TIMEOUT_MS);
    }

    @Tool(description =
            "双击组件正文进入编辑、全量替换文字、回车确认，返回组件新 aria-label。"
                    + "多行文本用 \\n 换行。")
    public ToolResultBlock edit_text(RuntimeContext context,
            @ToolParam(name = "componentId", description = "组件 data-id") String componentId,
            @ToolParam(name = "text", description = "新文字") String text) {
        return support.run(canvasId(context), """
                async function __op(componentId, text) {
                    const editSel = await page.evaluate((cid) => {
                        const el = document.querySelector('.canvas-component[data-id="' + cid + '"]');
                        if (!el) return null;
                        if (el.querySelector('.component-body strong')) {
                            return '.canvas-component[data-id="' + cid + '"] .component-body strong';
                        }
                        if (el.querySelector('.component-body p')) {
                            return '.canvas-component[data-id="' + cid + '"] .component-body p';
                        }
                        return '.canvas-component[data-id="' + cid + '"] .component-body';
                    }, componentId);
                    if (!editSel) throw new Error('组件不存在: ' + componentId);
                    await page.dblclick(editSel, { force: true });
                    await page.waitForFunction(() => {
                        const el = document.querySelector('[contenteditable="plaintext-only"]');
                        return el && document.activeElement === el;
                    }, undefined, { timeout: 3000 });
                    await page.keyboard.paste(text);
                    await page.keyboard.press('Enter');
                    const after = await page.evaluate((cid) => {
                        const el = document.querySelector('.canvas-component[data-id="' + cid + '"]');
                        if (!el) return null;
                        const strong = el.querySelector('.component-body strong');
                        return {
                            label: el.getAttribute('aria-label'),
                            content: strong ? strong.textContent : el.querySelector('.component-body').textContent
                        };
                    }, componentId);
                    if (!after) throw new Error('组件不存在: ' + componentId);
                    return after.label;
                }
                const __r = await __op(%s, %s);
                console.log('__HB_RESULT__ ' + JSON.stringify({ label: __r }));
                """.formatted(quote(componentId), quote(text)), DEFAULT_TIMEOUT_MS);
    }

    @Tool(description =
            "删除组件：先点击选中（使删除按钮出现），再点击删除按钮，验证组件数量减一。")
    public ToolResultBlock delete_component(RuntimeContext context,
            @ToolParam(name = "componentId", description = "组件 data-id") String componentId) {
        return support.run(canvasId(context), """
                async function __op(componentId) {
                    const before = await page.evaluate(() =>
                        document.querySelectorAll('.canvas-component').length);
                    await page.click('.canvas-component[data-id="' + componentId + '"]', { force: true });
                    await page.waitForSelector('#delete-component:not([hidden])', { timeout: 3000 });
                    await page.click('#delete-component');
                    await page.waitForFunction((n) => document.querySelectorAll('.canvas-component').length === n - 1,
                            before, { timeout: 3000 });
                    return true;
                }
                const __r = await __op(%s);
                console.log('__HB_RESULT__ ' + JSON.stringify({ deleted: __r }));
                """.formatted(quote(componentId)), DEFAULT_TIMEOUT_MS);
    }

    @Tool(description =
            "设置组件颜色。property 取 text（文字颜色）或 background（背景/线条颜色），"
                    + "color 为 #rrggbb 六位十六进制或 default（恢复默认）。divider 组件只有 background（线条）。")
    public ToolResultBlock set_color(RuntimeContext context,
            @ToolParam(name = "componentId", description = "组件 data-id") String componentId,
            @ToolParam(name = "property", description = "text / background") String property,
            @ToolParam(name = "color", description = "#rrggbb 或 default") String color) {
        if (!List.of("text", "background").contains(property)) {
            return ToolResultBlock.error("无效颜色属性: " + property);
        }
        if (!"default".equals(color) && !color.matches("^#[0-9a-fA-F]{6}$")) {
            return ToolResultBlock.error("无效颜色格式: " + color + "（应为 #rrggbb 或 default）");
        }
        String value = "default".equals(color) ? "default" : color.toLowerCase(Locale.ROOT);
        return support.run(canvasId(context), """
                async function __op(componentId, property, color) {
                    await page.click('.canvas-component[data-id="' + componentId + '"]', { force: true });
                    await page.waitForSelector('#component-width:not([hidden])', { timeout: 3000 });
                    await page.click(property === 'text' ? '#text-color-button' : '#background-color-button');
                    await page.waitForSelector('#color-palette[open]', { timeout: 3000 });
                    try {
                        if (color === 'default') {
                            await page.click('#color-default');
                        } else {
                            await page.fill('#color-hex', color);
                        }
                        await page.click('#color-confirm');
                        await page.waitForFunction(() =>
                            !document.getElementById('color-palette')?.open,
                            undefined, { timeout: 3000 });
                    } finally {
                        const __open = await page.evaluate(() =>
                            Boolean(document.getElementById('color-palette')?.open));
                        if (__open) {
                            await page.keyboard.press('Escape');
                            await page.waitForTimeout(150);
                        }
                    }
                    const after = await page.evaluate((cid) => {
                        const el = document.querySelector('.canvas-component[data-id="' + cid + '"]');
                        if (!el) return null;
                        const body = el.querySelector('.component-body');
                        const cs = getComputedStyle(body);
                        return { text: cs.color, background: cs.backgroundColor };
                    }, componentId);
                    if (!after) throw new Error('组件不存在: ' + componentId);
                    return after;
                }
                const __r = await __op(%s, %s, %s);
                console.log('__HB_RESULT__ ' + JSON.stringify({ color: __r }));
                """.formatted(quote(componentId), quote(property), quote(value)), DEFAULT_TIMEOUT_MS);
    }

    @Tool(description =
            "设置组件动画。trigger 取 click（点击触发）或 hover（悬停触发），"
                    + "effect 取 none（无）/ shadow（阴影加重）/ scale（放大）/ rotate（旋转一圈）。")
    public ToolResultBlock set_animation(RuntimeContext context,
            @ToolParam(name = "componentId", description = "组件 data-id") String componentId,
            @ToolParam(name = "trigger", description = "click / hover") String trigger,
            @ToolParam(name = "effect", description = "none / shadow / scale / rotate") String effect) {
        if (!List.of("click", "hover").contains(trigger)) {
            return ToolResultBlock.error("无效触发方式: " + trigger);
        }
        if (!List.of("none", "shadow", "scale", "rotate").contains(effect)) {
            return ToolResultBlock.error("无效动画效果: " + effect);
        }
        return support.run(canvasId(context), """
                async function __op(componentId, trigger, effect) {
                    await page.click('.canvas-component[data-id="' + componentId + '"]', { force: true });
                    await page.waitForSelector('#component-width:not([hidden])', { timeout: 3000 });
                    await page.selectOption('#animation-trigger', { value: trigger });
                    await page.selectOption('#animation-effect', { value: effect });
                    const after = await page.evaluate(() => ({
                        trigger: document.getElementById('animation-trigger').value,
                        effect: document.getElementById('animation-effect').value
                    }));
                    return after;
                }
                const __r = await __op(%s, %s, %s);
                console.log('__HB_RESULT__ ' + JSON.stringify({ animation: __r }));
                """.formatted(quote(componentId), quote(trigger), quote(effect)), DEFAULT_TIMEOUT_MS);
    }

    @Tool(description =
            "修改画布尺寸（画布面板标题栏的原生 select），取值 960x600 / 1280x720 / 1920x1080 / 390x844。"
                    + "修改后画布缩放比例会变化，后续坐标需重新读取。")
    public ToolResultBlock set_canvas_size(RuntimeContext context,
            @ToolParam(name = "size", description = "画布尺寸（宽x高）：960x600 / 1280x720 / 1920x1080 / 390x844") String size) {
        if (!List.of("960x600", "1280x720", "1920x1080", "390x844").contains(size)) {
            return ToolResultBlock.error("无效画布尺寸: " + size);
        }
        return support.run(canvasId(context), """
                await page.selectOption('#canvas-size', { value: %s });
                const __r = await page.evaluate(() => document.getElementById('canvas').style.width + 'x'
                        + document.getElementById('canvas').style.height);
                console.log('__HB_RESULT__ ' + JSON.stringify({ canvasSize: __r }));
                """.formatted(quote(size)), DEFAULT_TIMEOUT_MS);
    }

    private static String canvasId(RuntimeContext context) {
        return context == null ? null : context.getSessionId();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
