# 组件与动画编辑器 Demo

原生 HTML/CSS/JavaScript 组件编辑器，界面占满浏览器窗口：中间为等比适配的画布，右侧依次为组件库和 AI 助手，下方可切换时间轴与属性。画布可选 `960 × 600`、`1280 × 720`、`1920 × 1080`、`390 × 844`，调整尺寸时保留组件位置，并将超出边界的组件移回画布内。

右侧 AI 助手可以对话修改画布：可以附带最多 3 张参考图，也可以输入 `/` 选择 Skill；Agent 通过画布专用工具驱动 ego-browser，在你正看着的浏览器标签页里实时修改组件（拖入、移动、缩放、改文字、变色、加动画、删除），你全程可见。

## 组件编辑器操作

拖动画布与组件库之间的竖分隔条调整左右宽度，拖动底部面板上方的横分隔条调整上下高度；画布随分区尺寸等比适配。分隔条支持聚焦后方向键调整，Home / End 调至允许的最小 / 最大尺寸。分区有最小尺寸限制，窗口变小时自动收敛。

画布默认「铺满宽度」，按可用宽度等比显示，超出高度时在画布区内滚动。「− / ＋」手动缩放，点击百分比恢复 100%；「适应窗口」完整显示整张画布，「铺满宽度」减少左右留白。手动缩放范围 10%～800%，放大后可水平、垂直滚动；缩放只影响显示，不修改画布实际尺寸、组件坐标或动画。

1. 从右侧拖入按钮、文本、卡片、标题、标签或分隔线，也可点击组件库添加；同一种组件可以添加多次。
2. 编辑模式双击组件文字可直接在画布上编辑；卡片标题和描述分别编辑。Enter 或点击别处保存，Esc 取消，Shift+Enter 换行。也可在下方「属性」中修改文字和宽高；拖动组件调整位置，拖动四角调整大小。
3. 给按钮配置「点击时 → 旋转一圈」，给卡片配置「鼠标悬停时 → 阴影加重」。
4. 切换「预览」，点击按钮、悬停卡片查看动画；切回「编辑」调整位置。选中组件后可点击「删除组件」删除；分隔线不显示文字编辑项。
5. 选中组件后，在「属性 → 颜色」中设置文字或背景色；分隔线设置线条色。颜色编辑器提供色相 / 饱和度区域、亮度条、HEX 输入及 RGB 输入，支持基本颜色、24 个自定义颜色槽位和恢复默认。颜色为 `#RRGGBB` 十六进制格式。
6. 底部「时间轴」固定总长 10 秒，每个组件一条轨道、一段动画。点击「添加动画」，选择阴影、放大或旋转，设置开始时间和时长（单位毫秒：开始 ≥ 0，时长 ≥ 100，开始 + 时长 ≤ 10000）；拖动动画块改变开始时间，拖动右边缘改变时长，以 0.1 秒为步长。点击播放自动进入预览，支持暂停、继续、停止和点击刻度定位播放头。

## AI 助手（对话改画布）

右侧「AI 助手」面板输入想改的效果（如「把标题改成红色并移到右上角」），发送后：

1. 前端先上传附带的图片（`POST /api/canvases/{id}/images`），再调用 `POST /api/canvases/{id}/generate`，同步等待；
2. 业务端校验消息、图片数量和忙碌状态，读取当前画布后把「用户消息 + 画布 JSON + 页面地址 + Skill/图片路径」转发给 Agent（`POST /internal/runs`）；
3. Agent 加载 `canvas-editor` skill，通过画布工具（`read_canvas` / `drag_component` / `move_component` / `resize_component` / `edit_text` / `delete_component` / `set_color` / `set_animation` / `set_canvas_size` / `create_canvas_space`）绑定你的 ego lite 画布标签页，用真实鼠标/键盘操作页面；页面前端自动保存，你实时看到变化；
4. 生成期间前端每秒轮询 `/history` 和 `/rounds` 更新对话与执行轨迹；完成后重新拉取画布并渲染，对话框提示结果。

参考图存放在 `sketch-data/<canvasId>/uploads/`。生成期间画布锁定编辑，请勿刷新或关闭画布标签页；同一时间只有一个修改任务在进行。

## 运行前提

- JDK 17、Maven。
- ego lite 已安装并正在运行；Agent 会从 `~/.local/share/ego` 解析 `ego-browser` 路径，并写入 `agent-state/scripts/ego-browser-path.txt`。
- ego lite 不是 headless 浏览器：Agent 操作时需要浏览器窗口保持打开。
- 百炼 API Key，默认模型 `dashscope:gui-plus`（统一走 DashScope OpenAI 兼容端点），可用 `DASHSCOPE_MODEL` 覆盖。

## 启动

两个服务独立启动（Agent 不再拉起业务进程）。

```powershell
# 1. 打包（打包前停止正在运行的服务，Windows 会锁住 JAR）
mvn clean package

# 2. 启动业务端（8086，存画布数据）
java '-Dfile.encoding=UTF-8' -jar .\web-app\target\web-app.jar

# 3. 另开终端配置 API Key 并启动 Agent（8083）
$env:DASHSCOPE_API_KEY = Read-Host 'DASHSCOPE_API_KEY' -MaskInput
java '-Dfile.encoding=UTF-8' -jar .\agent-stdio\target\agent-stdio.jar
```

打开 [组件编辑器](http://127.0.0.1:8086/)。也可以在 IDEA 中直接运行 `demo.hb.web.WebApplication` 和 `demo.hb.agent.AgentApplication`，工作目录设为项目根目录，VM options 填 `-Dfile.encoding=UTF-8`。

首次打开页面会自动创建画布；画布 ID 保存在 localStorage（`hb.component-studio.canvas-id`），可用 `?canvas=<id>` 打开指定画布。画布数据由业务端持久化到 `sketch-data/<canvasId>/page.json`。

## 执行链路

1. 前端 `POST /api/canvases/{id}/generate`（可带 `skillId` 和最多 3 张图片），业务端校验消息/图片并检查忙碌状态，读取 `sketch-data/<canvasId>/page.json` 后同步转发 Agent 的 `/internal/runs`。
2. Agent 同步执行：`HtmlGenerationService` 组装任务提示词（用户消息、canvasId、页面地址、Skill/图片说明；画布 JSON 只传长度，不塞进提示词），以画布 ID 为会话保留对话记忆，调用一次 `agent.call`；图片以内容块随消息发送。
3. 模型加载 `canvas-editor` skill，调用画布专用工具：`CanvasToolSupport` 首次为画布创建/恢复 ego 任务空间并绑定 `?canvas=<id>` 标签页，之后复用；读取用 `page.evaluate`，修改用 `page.mouse` / `page.keyboard` 真实操作。页面自身防抖保存（`PUT /api/canvases/{id}/page`）落库。
4. 前端生成期间每秒轮询 `/history`、`/rounds`，收到响应后重新拉取画布并渲染。无事件流、无版本历史、无 MCP；业务端 `busy` 与 Agent 端 `running` 均为内存标志，重启即失；会话日志持久化在 `agent-state/session-log/`。
5. Agent 端已禁用 memory tools/hooks；`agent-state/memory/` 是旧版本遗留数据，不会被读取或写入。

## 关键代码位置

- `web-app`：
  - `CanvasController`：画布创建/列表/删除、`GET/PUT /{id}/page`、`POST /{id}/generate`、`POST /{id}/images`、`GET /{id}/history|rounds|rounds/{runId}`
  - `CanvasService`：消息与图片校验、忙碌锁、同步转调 Agent、错误映射
  - `CanvasPageValidator`：画布 schema、`#RRGGBB` 颜色、毫秒时间轴、坐标边界校验
  - `CanvasImages`：`sketch-data/{canvasId}/uploads/` 图片存取
  - `CanvasRepository`：`sketch-data/{canvasId}/page.json` 原子读写与编号
  - `AgentDispatchClient`：转发 `/internal/runs`，读取会话视图接口，解析 Agent 错误码
- `agent-stdio`：
  - `AgentRunController` / `AgentRunService`：同步执行接口 + 单任务并发保护 + 图片参数校验
  - `HtmlGenerationService`：组装任务、图片内容块、调用模型、错误分类
  - `HtmlAgentFactory`：模型与工具构建；注册画布工具，禁用子 Agent 与记忆工具/钩子；统一走 DashScope OpenAI 兼容端点；解析 ego-browser 路径
  - `CanvasTools` / `CanvasToolSupport`：画布专用工具、ego 页面绑定、真实鼠标/键盘执行与结果解析
  - `SessionRepository` / `SessionViewService`：会话 journal 存储、历史/轮次/单轮只读投影
  - `skills/canvas-editor/`：画布操作手册，模型通过 `load_skill_through_path` 加载
- 前端 `web-app/src/main/resources/static/`：
  - `component-editor.js`：画布编辑器 + `window.__editorApi`（忙碌锁、服务端重载）+ 自动保存
  - `chat.js`：AI 助手面板（发消息、图片上传、Skill 选择、轮询历史/轨迹、错误提示）
  - `trajectory.js`：执行轨迹渲染
  - `index.html` / `component-editor.css`：布局与样式

## YAML 配置

- `web-app/src/main/resources/application.yml` 的 `sketch` 段：
  - `data-directory`：画布数据目录，默认 `./sketch-data`，可用 `SKETCH_DATA_DIRECTORY` 覆盖；
  - `ui-url`：画布页面地址（供 Agent 打开），可用 `SKETCH_UI_URL` 覆盖；
  - `agent.url / connect-timeout / request-timeout`：Agent 地址与同步等待超时（默认 3600m）；
  - `generation.max-page-bytes`：画布 JSON 大小上限，默认 512KB。
- `agent-stdio/src/main/resources/application.yml`：
  - `server`：Agent HTTP 地址（默认 8083）；
  - `api-key / model`：`DASHSCOPE_API_KEY`、`DASHSCOPE_MODEL`（默认 `dashscope:gui-plus`，运行时映射为 `openai:gui-plus`）；
  - 模型地址：固定为 `https://dashscope.aliyuncs.com/compatible-mode/v1`；
  - `enable-thinking`：是否开启思考（默认 `false`）；
  - `timeout-seconds`：单次模型执行超时（默认 650）；
  - `max-dispatch-bytes`：派发请求体上限（默认 1MB）；
  - `compaction`：上下文压缩（`trigger-messages: 40`、`trigger-tokens: 40000`，保留最近 `keep-messages: 10` 条消息）；
  - `system-prompt / user-prompt`：画布编辑指令。

SessionLog 保存在 `agent-state/session-log/`（Windows 为 `journal.sqlite3`），Agent 状态在 `agent-state/state/`；`/internal/sessions/{canvasId}/history|rounds|rounds/{runId}` 由 `SessionViewService` 投影后供业务端和前端读取。`agent-state/memory/` 是旧版本遗留数据，当前配置已禁用 memory tools/hooks，不再读写。

`docs/ui-agent-runtime.md` 是旧的 Playwright 架构记录，仅作历史参考。
