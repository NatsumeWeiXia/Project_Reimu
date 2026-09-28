# Project Scarlet 会话工作台：前端开发落地方案

**版本：V1.0｜编制日期：2026-09-24｜状态：开发设计与联调契约草案**

**面向：前端、后端、测试与 AI 编程代理。** 目标是在已有 Java 17 / Spring Boot / AgentHarness 系统上，交付“左侧会话导航—中间聊天—右侧内容工作区”的可用产品，不再建设一套独立 Agent 后端。

本文的接口、事件、类型、默认尺寸、容量和验收指标均为本次建议设计；明确标为“已核实”的部分才属于当前仓库事实。本次未修改仓库、未安装完整前端依赖、未运行真实页面或后端集成测试。附件中的纯 TypeScript 契约和联调样例有单独的校验记录，不代表应用已经实现。

---

## 0. 执行摘要与不可变决策

采用 **现有 React 18 + TypeScript + Vite，新增 assistant-ui 的 ExternalStoreRuntime，保留 React Flow，使用 TanStack Table v8 与 react-markdown，直接连接 Java REST / SSE**。

采用组件而不是完整聊天平台；不引入 LibreChat/Open WebUI 的用户、会话和 Agent 服务；不需要 Node.js 生产运行时；不在第一版引入第二套 AG-UI 客户端与状态系统。

五项架构约束：

1. 后端是会话、消息、运行、权限、审批和产物版本的唯一权威来源。
2. 前端只维护一份可恢复的会话投影；assistant-ui 只做消息转换和交互，不另存历史。
3. 消息保存产物引用，产物正文独立存储；历史消息指向明确版本。
4. 打开、关闭、缩放、排序和切换内容面板不触发模型调用。
5. 所有执行请求与内容修改经过平台接口；浏览器不直接调用模型、MCP 或业务写工具。

### 0.1 本次核实的仓库基线

读取基线：`NatsumeWeiXia/Project_scarlet`，PR #1，分支 `feat/v1-java17-scaffold`，提交 `e100f0fa0e46b625ff4222f0af0a428b9a8b4b2b`。读取时 PR 为 open、未合并；不可把分支内容说成 main 的已有功能。[S1]

| 核实项 | 当前事实 | 本次处理 |
|---|---|---|
| 前端依赖 | React/React DOM 18.3.1；React Flow 12.8.5；TypeScript 5.9.2；Vite 7.1.3。[S2] | 先保留架构与 React 18，安全补丁及新增库在 M0 锁定并回归 |
| 前端组织 | `App.tsx` 集中了四个入口、请求函数和多种状态；运行详情每秒轮询；Token 存在 sessionStorage。[S3] | 提取 LegacyConsole、统一请求层和身份层；新工作台模块化开发 |
| 现有入口 | Agent 管理、工作流、运行调试、资源设置。[S3] | 保留，移至 `/app/admin/*`，不拿新聊天页替换全部功能 |
| 现有事件 API | `GET /runs/{id}/events` 是 JSON；`GET /runs/{id}/stream` 才是 SSE；均位于 `/api/v1`。[S4] | 不将 `/events` 当流接口；新会话接口沿用 events/stream 命名区别 |
| 现有执行 API | 已有启动、补充消息、按 questionId 回答、Run 取消与预算追加。[S4] | 可复用业务服务，但新 UI 需要请求回执、权限及事件投影适配 |
| 当前能力限制 | PR 声明尚未接入 AgentScope，单管理员、单 Worker，缺少长期记忆和细粒度权限等。[S1] | 新 UI 不伪造这些能力已实现；由 capability 显式控制入口 |

上一份《AgentScope Java 通用 Agent Harness 开发落地方案 V1.0》已经规划 React 工作台、REST/SSE、Conversation/Run/AgentInstance 与 ArtifactService。本文件细化前端及其必要的 API 增量，不改变既有任务调度和恢复边界。[B1]

### 0.2 三条版本兼容说明

assistant-ui 官方说明支持 React 18/19；采用现成 shadcn 代码时需要注意 React 18 的 `forwardRef` 兼容。本项目优先使用 primitives 加自己的样式封装，避免把 React 19 模板原样复制进来。[S5]

`react-resizable-panels` 当前文档使用 `Group / Panel / Separator`，不同主版本的 API 和尺寸单位有变化；不能混用旧的 `PanelGroup/PanelResizeHandle` 示例。包装成自己的 `WorkspaceSplitter`，在锁定版本后做键盘与尺寸测试。[S7]

本方案明确使用 **TanStack Table v8** 文档和 API；不把当前 `/latest` 的其他版本或框架示例混入 v8 工程。[S9]

---

## 1. 交付范围

### 1.1 V1.0 必须交付

| 编号 | 功能 | 用户得到什么 |
|---|---|---|
| F01 | 会话导航 | 新建、搜索、分组、重命名、置顶、归档、删除、用户与工作空间入口 |
| F02 | 多轮聊天 | 流式文本、Markdown、小表格、附件、复制、明确的执行状态 |
| F03 | 运行交互 | 补问、审批、停止当前任务、错误与网络恢复、预算提示 |
| F04 | 内容工作区 | 点击卡片打开、调整宽度、最大化、关闭、多个内容标签与版本选择 |
| F05 | React Flow 产物 | 查看、缩放、选择节点、节点详情、引用选择、Agent 修改后查看新版本 |
| F06 | 数据表产物 | 类型化列、服务端分页排序筛选、选中行、导出与引用 |
| F07 | Markdown 产物 | 只读预览、标题导航、引用、段落选择、下载 |
| F08 | 网址产物 | 站内内容、允许嵌入页面、明确的外链降级；不保证任意网站可 iframe |
| F09 | 内容与聊天联动 | 将指定版本、节点、行或段落作为显式引用提交给 Agent |
| F10 | 可恢复与隔离 | 刷新恢复、断线补齐、不串会话、不越权、旧版本可追溯 |

附件支持分两档：无多模态模型时，文档作为受控文件进入解析或检索；只有后端确认支持图像输入且上传检测完成，才启用图像消息。不能把上传成功等同于模型已经读取文件。

### 1.2 本次不扩建的范围

完整 Tool/Skill/MCP/知识库管理后台沿用后台方案，不在会话页重复建设。语音、视频、实时多人协作文档、任意生成代码执行、完整 IDE、自由画布流程发布与会话分支编辑不作为 V1.0 上线前置条件。

V1.0 可通过聊天请求修改产物并生成新版本；手动改写 Markdown、直接修改表格单元格、直接编辑可执行流程结构留到 V1.1。图的平移、缩放和临时布局调整不是业务内容修改。

### 1.3 功能启用条件

`GET /api/v1/ui-capabilities` 返回 `protocolVersion`、认证模式、上传限制、支持的产物类型和可用交互。

缺少后端能力时，必须显示“当前部署未启用”，或隐藏无用入口；不能以本地内存假数据替代生产历史。Mock 模式单独标注，只用于研发和验收夹具。

---

## 2. 用户故事与主流程

### 2.1 新建并开始会话

用户点击“新建会话”进入本地草稿页，选择已发布 Agent 或工作流；未发送时不创建空数据库会话。首次发送依次进行幂等创建会话和幂等提交消息，成功后替换路由为实际 conversationId。创建成功而消息失败时保留已创建 ID，不再次创建会话。

消息提交后立即显示用户气泡和“已提交/排队”；收到后端运行事件才显示“正在处理”。用户再次点击或网络重试沿用同一 clientRequestId；修改原文后的重新发送必须使用新 ID。

### 2.2 生成并查看内容

Agent 生成流程图、数据表、报告或网址结果；后端先校验并提交产物，再发出 `artifact.available` 事件。聊天显示带标题和版本的卡片。用户点击卡片，在右侧读取指定 revision 并渲染；不用正则从普通代码块中猜产物类型。

首次产物到达只显示卡片，不强行打开面板或抢焦点。已打开同一产物的新版本到达时显示提示，不自动替换用户正在看的旧版。

### 2.3 对选中内容继续提问

用户在右侧选择节点、行或段落，点击“引用到对话”；输入框出现可删除的引用标签。发送包含原文问题和结构化选择，后端重新鉴权、读取原版本并补充上下文。Agent 产生新版本后，界面提供“查看新版本/比较版本”。历史消息仍指向原 revision。

### 2.4 等待用户或审批

补问显示 questionId 对应卡片；答案提交至问题专用接口，不能伪装成一般聊天消息。审批展示实际目标、脱敏参数、风险、有效期；批准只是批准该操作，不显示“操作成功”，成功以工具回执为准。

### 2.5 刷新、换会话和取消

刷新先加载快照，再接续事件。切换会话仅解除旧视图订阅，后台任务继续；停止按钮明确写“停止本次任务”，调用 Run 取消请求。前端显示“正在停止”，收到确认后才显示“已停止”；关闭浏览器、AbortController 中止订阅、模型文本流结束均不等于取消业务任务。

---

## 3. 页面布局与交互规格

以下数值是实现初始值，不是第三方产品规范或已经测得的最优值。桌面优先，同时保证窄屏可操作。

### 3.1 桌面结构

```text
ConversationWorkspace
  ├─ 左侧：ConversationSidebar
  │    新建 / 搜索 / 分组历史 / 用户与工作空间
  ├─ 中间：ChatPanel
  │    标题 / Agent与运行状态 / 消息 / 补问审批 / 输入区
  └─ 右侧：ArtifactPanel（默认关闭）
       标签 / 标题与版本 / 渲染区 / 引用与下载等操作
```

| 参数 | 建议初始值 |
|---|---|
| 左侧展开 | 248px；允许在 224–320px 内调整 |
| 左侧收起 | 56px，仅保留导航按钮 |
| 中间最小宽度 | 420px；宽屏单栏消息正文最大 880px |
| 右侧最小宽度 | 500px；首次打开约占主内容区 48% |
| 顶栏 / 标签栏 | 56px / 40px |
| 正文 / 次级文字 | 15px、行高 1.65 / 13px |
| 输入框 | 最小 88px；最高 240px，超出内部滚动 |
| 间距体系 | 4、8、12、16、24、32px |
| 图标按钮 | 可视 32px，交互区域至少 40px |
| 产物标签上限 | 初始 5 个；超出时关闭最久未使用的只读标签，固定标签不自动关闭 |

外层 `height:100dvh`，保留兼容回退；三栏容器必须 `min-width:0; min-height:0`。消息、侧栏和产物各自滚动，外层不出现重复纵向滚动条。分隔条支持鼠标、触控与键盘，拖动时暂停 iframe 的指针捕获。

### 3.2 响应式规则

| 视口宽度 | 行为 |
|---|---|
| ≥1440px | 完整三栏；右栏关闭时聊天居中 |
| 1100–1439px | 打开右栏时自动将左栏折叠为 56px；用户可恢复 |
| 768–1099px | 左侧抽屉；内容工作区切换为覆盖主内容的面板，保留返回聊天 |
| 360–767px | 聊天和产物作为两个整页视图切换；不强挤三栏 |

不能以设备类别判断布局；以可用容器宽度决定。浏览器 200% 缩放也走上述退化路径。移动输入区避开软键盘与安全区。

### 3.3 左侧导航

历史按用户当前时区分为今天、昨天、近七天、更早；服务端保存 UTC 时间，前端格式化。置顶独立分组，搜索返回跨全部历史结果而非只过滤已经加载的 30 条。

查询默认每页 30 条，搜索 debounce 300ms，快慢请求竞态由 AbortController 和查询键共同控制。重命名需保留焦点和错误回退；标题默认截断，悬停或详情显示完整标题。

删除有确认；正在运行的会话禁止直接删除，提示先停止任务或使用归档。删除不等于撤销已经发生的外部操作，也不自动删除已确认长期记忆；具体级联策略由后端返回并展示。

用户区仅显示真实身份、当前工作空间和必要设置。旧单 Token 环境只能标注“本地管理员”，不伪造多用户头像、角色或组织。

### 3.4 聊天区

顶部显示标题、当前入口 Agent/工作流及固定版本、网络同步状态、当前活动。多 Agent 内部日志折叠进执行详情，默认不把每个子 Agent 输出都当成主助手答复。

中文输入法组合期间禁止 Enter 发送；普通 Enter 发送、Shift+Enter 换行，允许用户设置。输入内容为空且无附件/引用时禁发。正在执行时文本仍可编辑，但默认不支持无语义的并发新消息；只有后端允许 `supplement` 时才显示“补充当前任务”。

用户离消息底部超过约 96px 后停止自动滚动，显示“有新消息/回到底部”；恢复历史上翻时保持消息锚点，不因图片或 Markdown 完成排版而跳动。

复制仅复制用户可见正文。工具原始密钥、系统提示词、隐藏思维链与未经授权的内部日志不进入浏览器；调试入口展示执行摘要、证据与工具回执。

### 3.5 右侧工作区

标签唯一键为 `artifactId@revision`；不同版本可并列。切换标签保存该版本的缩放、分页、滚动位置和选择状态。最大化不新建路由上下文或消息流。关闭后焦点返回触发卡片。

产物错误只在右栏呈现，不让聊天整页崩溃。未知类型显示元信息和允许的下载入口；失权立即清空正文和选择，不能继续显示缓存副本。

### 3.6 主题与基础组件

沿用 Scarlet 品牌和现有风格；新增 CSS Modules 与命名空间化设计变量，不引入会重置旧页面全局样式的未审查 CSS preflight。默认跟随系统，提供明/暗切换；主题不进入 Agent 上下文。

深色初始 tokens：页面 `#0B1018`、侧栏 `#101722`、内容面 `#151E2B`、边界 `#2A3648`、主文字 `#E8EDF5`、次文字 `#A8B5C8`、强调 `#7AA2FF`。浅色采用独立 tokens，不做简单反色。颜色、悬停、聚焦与禁用状态进入组件测试；状态还必须有文字或图标，不能只靠颜色区分。

---

## 4. 技术栈与版本锁定

| 模块 | 选择 | 使用边界 |
|---|---|---|
| 应用 | React 18 + TypeScript + Vite | 保留现有工程；不创建 Next.js 服务 |
| 路由 | React Router | 使用 BrowserRouter，后端/网关提供 SPA fallback |
| 聊天 | `@assistant-ui/react` | ExternalStoreRuntime；只接通已实现的回调能力。[S6] |
| 面板 | `react-resizable-panels` | 包装 WorkspaceSplitter，避免 API 泄露至业务页。[S7] |
| 图 | `@xyflow/react` | 已有 React Flow；使用白名单节点和结构化数据。[S8] |
| 表 | `@tanstack/react-table` v8 | 受控服务端分页、筛选和排序。[S9] |
| Markdown | `react-markdown` + `remark-gfm` | 统一安全渲染组件，不执行原始 HTML。[S10] |
| 服务端资源缓存 | `@tanstack/react-query` | 会话列表、Agent 目录、产物正文、表格页、用户资料 |
| 活跃会话投影 | `zustand` | 消息、事件游标、活动、工具、问题和审批的前端投影 |
| UI 局部状态 | React state / 单独 UI store | 标签、选择、输入草稿、布局；不复制消息 |
| 流式传输 | 浏览器 fetch + `eventsource-parser` | 支持 Bearer/Cookie、AbortSignal、增量解析。[S11][S12] |
| 边界校验 | Zod 或项目已有 JSON Schema 校验器 | 编译期 TS 类型不能替代运行期校验 |
| 测试 | Vitest、Testing Library、MSW、现有 Playwright | 状态机/组件/协议/真实浏览器四层 |
| 基础交互组件 | 自有 Button/Dialog/Menu + 必要 Radix primitives | 统一 React 18 ref、焦点与样式 |

M0 形成 `dependency-baseline.md`，记录每个新增库的精确版本、peerDependencies、许可证、安装日期、构建和兼容测试。使用 `npm install --save-exact` 固定经验证的版本并提交 `package-lock.json`；之后 CI 使用 `npm ci`。本方案不把未安装联调的滚动最新版本冒充已验证依赖组合，也不推荐直接复制一份未经验证的全量 package.json。

Vite 7 官方要求 Node.js 20.19+ 或 22.12+。构建环境选择满足要求、仍在维护的 Node 版本并固定；Node 仅用于构建与测试，不是 Java 线上运行前提。[S15]

不通过 `--force`、`--legacy-peer-deps`、关闭严格类型检查来掩盖版本冲突。安全扫描若要求升级现有补丁版本，应保留锁文件变更并回归旧四个入口。

---

## 5. 工程目录与模块边界

```text
frontend/
  src/
    app/
      App.tsx                    # 路由与 providers，不写业务状态机
      providers.tsx
      router.tsx
      styles/tokens.css
    legacy/
      LegacyConsole.tsx          # 原四个入口先整体迁入，再逐步拆分
      legacy.css
    core/
      api/http-client.ts
      api/api-error.ts
      api/generated/             # 后端 OpenAPI 生成的边界类型
      contracts/                 # 公共事件、消息和产物契约
      realtime/stream-client.ts
      realtime/stream-supervisor.ts
      realtime/event-reducer.ts
      security/url-policy.ts
      security/scope-key.ts
      security/cache-purge.ts
    features/
      identity/                  # /me、工作空间、登录失效与退出
      conversations/             # 列表、创建、标题、归档、删除
      chat/
        components/
        runtime/ScarletRuntimeProvider.tsx
        runtime/message-converter.ts
        state/conversation-store.ts
        services/chat-commands.ts
      execution/                 # 工具、补问、审批、运行摘要
      artifacts/
        components/ArtifactPanel.tsx
        registry/renderer-registry.ts
        renderers/graph/
        renderers/table/
        renderers/markdown/
        renderers/webpage/
        renderers/file/
        state/artifact-ui-store.ts
        services/context-reference.ts
      attachments/
    shared/
      ui/
      markdown/SafeMarkdown.tsx
      hooks/
  mocks/                         # 仅研发构建启用
  tests/
    unit/
    integration/
    e2e/
    fixtures/
  package.json
  package-lock.json
```

依赖方向固定为 `页面/组件 → feature service → core API/契约`。渲染器不能导入聊天 store 后直接发送消息；通过 `onReference` 输出结构化事件。chat 不直接操作 React Flow 内部对象；legacy 不反向依赖新聊天实现。

### 5.1 路由

| 路由 | 页面 |
|---|---|
| `/app/chat/new` | 新会话草稿 |
| `/app/chat/:conversationId` | 会话工作台 |
| `?artifact=:id&revision=:n` | 当前会话内指定产物深链接；需重新鉴权 |
| `/app/admin/agents` | 原 Agent 管理 |
| `/app/admin/workflows` | 原工作流配置 |
| `/app/admin/runs/:runId?` | 原运行调试 |
| `/app/admin/resources` | 原资源设置 |

URL 不携带 Bearer Token、模型密钥、文档正文、选择全文或敏感筛选值。打开非法 artifactId/revision 不清空会话，也不显示其他会话产物。

### 5.2 状态归属：一类数据一个写入者

| 数据 | 唯一前端管理入口 | 生命周期 |
|---|---|---|
| 会话目录、用户、Agent 清单 | TanStack Query | 基于身份和工作空间的查询键 |
| 活跃会话消息与运行状态 | ConversationStore + reducer | 快照初始化、公共事件增量更新 |
| 产物不可变 revision 正文 | TanStack Query | 按身份、工作空间、产物、revision 缓存 |
| 图选中/缩放、表分页、标签 | ArtifactUIStore | 按会话与产物版本区分 |
| 输入草稿、待发引用 | Composer state | 初版内存存储；可选后端草稿 |
| 面板宽度、主题 | UI 设置 | 本地仅存无敏感内容的偏好 |

不能同时把消息完整存入 Query、Zustand 和 assistant-ui 三份缓存。历史页由 API 获取后只归并进 ConversationStore；Query 只管理这一获取过程，不另做供组件读取的长期消息集合。

退出、身份变化或工作空间切换：取消所有请求和订阅、递增 scopeGeneration、清空 Query 和 store、撤销 Blob URL、清除剪贴板以外的应用可控敏感缓存。BroadcastChannel 可用于同源其他标签页收到退出通知，但不广播消息正文或凭据。

---

## 6. 数据契约与领域映射

附件 `contracts/contracts.ts` 给出可编译的建议类型，不导入 AgentScope 或 assistant-ui 内部类型。正式开发必须与后端 OpenAPI/JSON Schema 一起评审，生成或校验客户端类型，不能长期维护两套互相漂移的 DTO。

### 6.1 身份与对象

Conversation 是用户聊天容器；Run 是业务运行；AgentInstance 是独立执行上下文。一段会话可能对应多个 Run，一个 Run 可能持续多个用户输入；浏览器不通过 conversationId 自行决定 SDK sessionId。[B1]

请求的 tenant/principal 由认证上下文决定。workspaceId 即使出现在前端键或 URL 中，也不构成授权。Java `long` 对外统一采用十进制字符串表示 ID、消息顺序号等可能超过 JS 安全整数的字段；时间使用带时区的 ISO 8601 字符串；金额和高精度小数使用字符串加明确类型。

### 6.2 消息

每个 Message 包含 `id / conversationId / ordinal / entityVersion / role / status / parts / createdAt`，可关联 `requestId / clientRequestId / runId / turnId`。

`parts` 为判别联合：文本、产物引用、引用证据、附件引用、工具引用、补问引用、审批引用。业务卡片不伪装成文本或模型工具指令。引用只保存 ID、revision、必要显示标签，不把大正文塞入消息。

`ordinal` 决定持久消息顺序；不能用时间戳排序解决同毫秒并发。UI 临时消息通过 clientRequestId 与持久消息对账；实际 messageId 到达后替换临时项，不能追加第二个用户气泡。

### 6.3 产物

产物最小字段为 `artifactId / conversationId / revision / kind / schemaVersion / title / sourceRunId / contentHash / createdAt / permissions / content`。

V1 的 `kind` 为 `reactflow / table / markdown / webpage / file`。消息引用固定 revision，不使用 `latest`。内容哈希用于完整性与定位，不代表可访问权限；权限每次读取重新检查。

同一 revision 内容不可修改；撤权/删除元数据可以改变。一次修订创建新 revision，并关联父版本。复杂二进制由受控下载接口获取；前端不接收宿主路径、永久对象存储凭据或可访问任意资源的 URL。

### 6.4 上下文引用

发送体携带显式 `contextReferences`：

```json
{
  "clientRequestId": "client-request-42",
  "intent": "chat",
  "text": "把选中的两个步骤改成并行执行",
  "attachmentIds": [],
  "contextReferences": [{
    "artifactId": "artifact-flow-1",
    "revision": 1,
    "selection": {"type": "graph_nodes", "nodeIds": ["n2", "n3"]}
  }]
}
```

表格引用使用稳定 rowId、列 ID 和 querySnapshotId；Markdown 使用服务端块 ID 和可选选中文本/摘要。选中文字仅帮助定位，后端用原 revision 验证，不信任前端提供的“证据正文”。任意 DOM selection 偏移不能直接当作 Markdown 源码偏移。

默认上限：5 个产物引用；图 50 个节点；表 100 行、20 列；段落合计 8,000 字符；超限引导缩小选择或引用完整产物句柄。均为产品默认值，服务端再次执行限制。

---

## 7. 与 assistant-ui 的适配

使用 `ExternalStoreRuntime`，它支持已有状态源与自定义消息格式，通过回调决定是否启用编辑、重试、取消等能力。[S6]

`ScarletRuntimeProvider` 负责：读取当前 scope 的消息选择器、转换成展示模型、实现发送与取消回调、桥接附件适配器。它不连接 SSE，不写数据库，不启动模型，不直接执行 tool-call。

### 7.1 回调启用矩阵

| 回调/能力 | V1.0 策略 |
|---|---|
| `onNew` | 调用 ChatCommandService；解析完整文本 parts 与附件/引用，不能只取第一个文本块 |
| `onCancel` | 调平台 Run 取消命令；在服务端确认前保持 CANCEL_REQUESTED |
| `onEdit` | 不注册；历史编辑与副作用分支策略尚未开放 |
| `onReload` | 不注册；不允许重新生成按钮直接重放已执行工具 |
| `onAddToolResult` | 不注册；业务工具结果由服务端产生，不由浏览器伪造 |
| threadList 适配 | V1 由自己的 ConversationSidebar 管理路由；不再同步第二套远端列表 |
| 本地队列 | 不注册；排队/补充语义由平台 request 服务负责 |

V1 使用外部消息 ID 关联业务卡片，必要时通过自定义 Message 组件从平台 store 读取完整 parts。不要为了使用某个模板，强行将 artifact_ref 编造成 SDK tool-call。新版本确实支持 data part 时，可在 adapter 内转换，但平台 DTO 不改变。

相邻助手消息不能为了视觉合并而丢失 messageId、turnId 或产物来源。当前版本转换器的消息合并行为须写成契约测试；不能把 A Agent 的审批卡合并成 B Agent 的答复。

### 7.2 输入与运行状态

UI activity 使用 `idle / queued / generating / working / waiting_user / waiting_approval / paused_budget / cancelling / failed`，由平台投影返回，不在组件里猜 Run 状态。

`isRunning` 只反映界面是否处于正在执行/取消等待阶段；不能用它替代完整状态。补问和审批卡拥有专用表单，即便普通发送被禁用，也要能完成被授权的答复操作。

---

## 8. REST 接口：复用与增量清单

**全量新契约必须由后端补齐后才能验收。** 当前 `/runs/{id}/messages` 返回 Run，并不天然提供结构化 Message/Artifact 协议；现有 `sessionId` 也不是完整会话管理 API。[S4]

### 8.1 可复用的旧接口/业务服务

| 当前接口 | 当前用途 | 本次适配 |
|---|---|---|
| `GET /api/v1/agents` | Agent 目录 | 过滤可使用的发布版本，补齐权限 |
| `GET /api/v1/workflows` | 流程目录 | 新会话入口选择 |
| `POST /api/v1/runs` | 启动工作流，含 requestId 幂等 | 由会话命令服务调用，不让组件自己拼 Run |
| `GET /api/v1/runs/{id}` | 运行聚合 | 调试视图和过渡期只读适配 |
| `POST /api/v1/runs/{id}/messages` | 运行补充消息 | 增强幂等、状态验证与消息投影 |
| `POST /api/v1/runs/{id}/questions/{qid}/answer` | 补问答复 | 保留 questionId 和 requestId，增强授权 |
| `POST /api/v1/runs/{id}/cancel` | 取消 | 不冒充逐 token 停止；补充取消回执 |
| `GET /api/v1/runs/{id}/events?after=...` | JSON 事件列表 | 旧运行调试与迁移测试 |
| `GET /api/v1/runs/{id}/stream?after=...` | 运行 SSE | 旧调试保留；不再每秒拉完整 Run |

旧接口与新接口保持不同 adapter。原 `/runs/{id}/cancel` 和旧问题答复端点不在原路径上强行将 200 Run 改为 202 回执；标准化命令使用新增路径，旧客户端保持兼容。旧 Run 没有完整聊天数据时只展示“历史运行详情”，不从 agent.history 猜出一段精确的用户会话。

### 8.2 新增或标准化接口

| 接口 | 响应/关键规则 |
|---|---|
| `GET /api/v1/me` | 真实用户、工作空间、角色和权限版本 |
| `GET /api/v1/ui-capabilities` | 协议版本、功能开关、限制、支持 renderer kind |
| `GET /api/v1/conversations?cursor=&limit=&q=&archived=` | 游标分页，服务器搜索，下一游标 |
| `POST /api/v1/conversations` | clientRequestId、入口类型和固定发布版本；201 + conversationId |
| `PATCH /api/v1/conversations/{id}` | 标题/置顶/归档；expectedVersion，冲突不覆盖 |
| `DELETE /api/v1/conversations/{id}` | 活跃运行冲突、保留与级联策略；可返回异步删除回执 |
| `GET /api/v1/conversations/{id}/snapshot` | 最近 50 条消息、活动、工具/待决卡片、streamId、cursor、权限版本 |
| `GET /api/v1/conversations/{id}/messages?before=&limit=` | 更早历史，携带每条 entityVersion 和 ordinal |
| `POST /api/v1/conversations/{id}/messages` | 幂等请求；202 + requestId/clientRequestId/messageId/runId/disposition |
| `GET /api/v1/requests/by-client-id/{id}` | 找回超时请求回执；按当前身份/工作空间授权 |
| `GET /api/v1/conversations/{id}/events?after=&streamId=` | JSON 补齐，返回事件及 nextCursor |
| `GET /api/v1/conversations/{id}/stream?after=&streamId=` | SSE：公开会话投影事件，不输出原始 SDK 事件 |
| `POST /api/v1/runs/{id}/cancellation-requests` | 新增：clientRequestId 幂等、202 待确认；旧 cancel 端点保持原响应，避免破坏旧客户端 |
| `POST /api/v1/questions/{qid}/answers` | 新增：runId、questionRevision、clientRequestId、答案；保留旧 Run 问题接口 |
| `POST /api/v1/approvals/{id}/decisions` | approvalRevision、operationId、argsHash、decision、clientRequestId |
| `GET /api/v1/artifacts/{id}` | 可见元信息、可访问版本列表；不直接返回敏感正文 |
| `GET /api/v1/artifacts/{id}/revisions/{rev}` | 指定版本、权限、内容或分页内容句柄 |
| `POST /api/v1/artifacts/{id}/revisions/{rev}/table-queries` | 排序、筛选、pageSize；返回 queryId/querySnapshotId |
| `GET /api/v1/artifacts/{id}/table-queries/{qid}/rows?cursor=` | 在固定查询快照中分页；列类型和稳定 rowId |
| `POST /api/v1/artifacts/{id}/exports` | 指定 revision、querySnapshotId、格式；返回任务回执 |
| `GET /api/v1/artifacts/{id}/revisions/{rev}/download` | 服务端鉴权下载；文件名/类型受控 |
| `POST /api/v1/attachments` | multipart 上传，返回 attachmentId 和检测状态 |
| `GET /api/v1/attachments/{id}` | scanning/ready/rejected 与实际媒体类型 |
| `GET /api/v1/evidence/{id}` | 重新鉴权，返回原文位置、版本及可见片段 |

表格查询使用 POST 是为了避免敏感筛选进入 URL 日志；大表不是按任意列字段拼 SQL。queryId 与 querySnapshotId 绑定身份、产物版本和有效期。V1 手工产物写入 API 不开放给普通浏览器；Agent 通过受控服务生成 revision。

### 8.3 请求与错误标准

响应成功、错误和 SSE 均有协议版本。错误为 `code / message / traceId / retryable / details`；生产不返回 Java 堆栈和密钥。

| 状态/业务码 | 前端处理 |
|---|---|
| 401 | 暂停发新请求；最多一次统一凭据刷新，失败返回登录，清理所有敏感状态 |
| 403 / RESOURCE_NOT_AVAILABLE | 清理失权产物与选择；根据安全策略不区分不存在和无权 |
| 409 / VERSION_CONFLICT | 拉取最新对象，提供比较或重新提交，不自动覆盖 |
| 409 / IDEMPOTENCY_CONFLICT | 显示请求键冲突；不能自行换键并重复执行 |
| 410 / CURSOR_EXPIRED | 重新快照和订阅；不重新发送业务输入 |
| 413 / 415 / 422 | 指出文件大小、类型或结构问题，保留可编辑草稿 |
| 429 | 遵守 Retry-After；禁用短时间重试风暴 |
| 5xx / 网络超时 | GET 可受控重试；写请求先用原 clientRequestId 查询回执或重发相同幂等请求 |

提交内容改变后不能复用原幂等键。同一请求在 POST 与 SSE 到达顺序不一致时，以 requestId/clientRequestId 归并，不能假设 HTTP 回应一定先于事件。

---

## 9. SSE 与恢复协议

这是本次前后端必须共同完成的部分，不能只由 UI 修补。所有命令走 REST；SSE 只订阅结果与状态。

### 9.1 选择 fetch 读取事件流

当前工程使用 Bearer Authorization；浏览器原生 EventSource 构造参数没有通用自定义请求头入口。因此 V1 统一使用 `fetch + ReadableStream + eventsource-parser`；未来改为 Cookie 认证也继续复用该客户端，不并行维护两套传输逻辑。[S3][S11][S12]

解析器处理不完整 chunk、多行 data、LF/CRLF、注释心跳与 UTF-8 跨 chunk 字符。禁止 `chunk.split('\n\n')` 后直接 JSON.parse 作为生产实现。设置单事件/解析缓冲上限，异常则关闭连接并报告协议错误，不无限累积内存。[S11]

### 9.2 公开投影流，而不是原始 Run 日志

每个“当前身份 + 工作空间 + Conversation + 权限版本”有一个公开投影 `streamId`。这条流可以汇总多个 Run/Task 的用户可见事件，但禁止携带隐藏思维链、原始提示词、未授权工具参数与内部诊断。

业务事件信封：

```json
{
  "protocolVersion": 1,
  "eventId": "evt-003",
  "streamId": "stream-conv-1-policy-7",
  "conversationId": "conv-1",
  "workspaceId": "workspace-demo",
  "permissionVersion": "7",
  "cursor": "c3",
  "previousCursor": "c2",
  "occurredAt": "2026-09-24T04:00:03Z",
  "type": "message.text.delta",
  "data": {
    "messageId": "msg-assistant-1",
    "partId": "text-1",
    "attemptId": "attempt-1",
    "offsetUtf16": 0,
    "text": "已生成处理流程。"
  }
}
```

`cursor` 是不透明字符串，只做相等性判断，不能按数值 +1。`previousCursor` 属于同一公开投影，不是数据库全局 sequence；权限过滤造成的内部序号空洞不等于丢消息。streamId/permissionVersion 改变时必须重新快照。

SSE 的 `id:` 使用 cursor，`event:` 使用 type，`data:` 为完整信封。服务端在事件提交到可回放投影后才发布。文本 delta 可以按约 30–60ms 合并后持久化与推送，不要求每 token 一次事务。草稿文字持久化不表示业务工具或执行片段已经提交成功；失败后必须明确标注中断 attempt。

### 9.3 事件类型

| 事件 | 含义 | 前端动作 |
|---|---|---|
| `conversation.updated` | 标题、目录状态或版本变化 | 更新本地摘要并失效目录查询 |
| `message.upsert` | 完整消息及 entityVersion | 按 ID 和版本替换，兼做最终文本校正 |
| `message.text.delta` | 指定消息 part 的文本增量 | 校验 attempt 与 offset 后追加 |
| `activity.changed` | 执行/等待/取消等公共状态 | 更新顶部与输入区，不能推断工具执行成功 |
| `tool.updated` | 某次 operationId 的当前回执 | 更新工具卡；UNKNOWN 显示结果待核实 |
| `question.updated` | 新补问、答案受理或过期 | 更新对应问题卡，防止重复答复 |
| `approval.updated` | 请求、决策、撤销、过期 | 更新审批卡，操作成功仍看工具回执 |
| `artifact.available` | 已提交产物 revision 可读取 | 加入元信息缓存，消息引用由 message.upsert 承载 |
| `artifact.revoked` | 产物不可继续展示 | 清正文、标签内容、选中引用和下载状态 |

`stream.reset` 为控制消息：权限/协议/保留期等变化要求快照；不当作普通业务事件推进 cursor。心跳为注释或单独控制消息，不改变最后应用游标。

### 9.4 快照 + 回放流程

1. 建立 scopeKey，并为当前页面生成 connectionGeneration。
2. `GET snapshot` 返回同一水位的消息、活动和待决卡片，以及 `streamId/cursor/permissionVersion`。
3. 原子替换当前投影，记录 lastAppliedCursor。
4. 订阅 `stream?streamId=...&after=lastAppliedCursor`，服务端回放水位之后的事件再进入实时流。
5. 每个事件通过运行时 schema 校验、身份/会话/连接代次检查、cursor 链检查，再进入 reducer。
6. reducer 成功更新投影后才更新 lastAppliedCursor；收到但未应用的事件不算已消费。
7. 断线按 1、2、4、8 秒增加重连间隔，上限 30 秒并加入随机抖动；遵守服务端 Retry-After/retry 提示。
8. cursor 过期或链断裂，丢弃该快照之后未确认的展示增量，重新快照；不能补发原用户请求。

快照必须由后端提供一致水位，不能在前端分别拉消息、Run 和审批后自己拼一个“当前快照”。如权限变更，服务端主动终止旧投影；前端在窗口重新可见、重新连接、打开产物及敏感操作前复验权限。已经下载到用户设备的内容无法通过前端撤回，撤权只控制后续访问与应用可控缓存。

### 9.5 delta 幂等规则

`offsetUtf16` 明确采用 JS/Java 都可一致计算的 UTF-16 code unit 数；不得混用 UTF-8 字节、Unicode code point 或屏幕字符数。每个 text part 含 `attemptId`。

- offset 等于当前文本长度：追加。
- offset 较小，且覆盖范围中的文本完全相同：重复增量，不追加。
- offset 较大、范围不一致或 attemptId 不匹配：停止当前事件应用，重新快照或获取完整消息。
- `message.upsert` 具有更高 entityVersion 时，用完整正文校正；不能再把旧 attempt 增量接到新正文后。
- 页面切换后的旧 connectionGeneration 事件直接丢弃，不能更新新会话。

对未知必需事件版本应提示客户端需更新并停止错误应用；不能一边忽略未知业务语义一边推进游标。纯可选事件需由协议明确声明可忽略。

### 9.6 HTTP、SSE 与多标签页的竞态

事件可能先于 POST 202；以 clientRequestId/messageId 去重临时用户消息。取消请求可能先于最后文本块；收到取消请求后不立即丢掉所有回执，应等待服务端终态和最终完整消息。

同一浏览器标签页默认只订阅当前会话一条公开流；其他会话状态通过目录刷新或轻量通知更新。多个标签页可各自订阅，服务端处理限额和请求幂等；不要求第一版选举一个跨标签页流 leader。

一个长期聊天 Run 可在 IDLE 后继续，SSE EOF 不是业务完成信号。网络错误、代理超时、用户离开页面与 Run 终结分别展示。

### 9.7 代理部署要求

SSE 返回 `text/event-stream`，禁止缓存；反向代理关闭该路径的响应缓冲，设置大于心跳间隔的读超时。建议心跳 15 秒、无活动检测 45 秒，并在实际网关中验证。不能仅在 Vite 本地直连正常就认为线上流式可用。

---

## 10. ArtifactPanel 与四类渲染器

### 10.1 统一渲染接口

`ArtifactRendererRegistry` 按 kind 选择本地注册的渲染器。接口包含 `artifact / readOnly / selection / onSelectionChange / onReference / onRequestRevision`；接收经校验的数据，不接受任意组件名、JS 源码或动态 import URL。

每个渲染器独立 lazy import，并被 ErrorBoundary 包裹。首次打开先展示骨架；加载失败保留标题与重试。切换标签时使用 AbortSignal 防止旧结果覆盖；queryKey 必须包括完整身份作用域、artifactId 和 revision。

右栏操作统一放在工具栏：返回/关闭、版本、最大化、引用、下载、更多。下载不触发模型。大文件导出异步处理，不将数十万行加载到浏览器拼 CSV。

### 10.2 React Flow 渲染器

数据结构包含 `graphSchema / purpose / nodes / edges / layout`。节点的 `id/type/label/data` 与边的 `id/source/target/type` 都有 schema；类型只允许本地白名单。默认流程图节点可以是 agent、step、decision、start、end，不能接受模型返回任意组件代码。

React Flow 支持自定义节点、节点边状态与保存恢复；本项目使用受控 nodes/edges 适配，而不直接把框架序列化对象当业务模型。[S8]

| purpose | 默认交互 | 业务约束 |
|---|---|---|
| explanatory | 平移、缩放、选择、节点详情、临时拖动布局 | 无业务执行意义 |
| workflow_draft | 同上；通过聊天请求修订 | 新版本通过后端业务校验；不自动发布 |
| run_projection | 只读结构；状态叠加 | 来自 Run/Task 权威状态；拖拽不改变运行 |

布局优先级：有效的后端布局 → 已保存的本地只读视口/临时布局 → 浏览器 Web Worker 自动布局。首次内容完整且容器尺寸确定后调用 fitView；后续文本流、工具状态、标签切换不反复 fitView。`ResizeObserver` 监听容器变化，面板拖动过程中做限频布局。

节点详情在当前右栏内部切换或使用局部抽屉，不再额外向右打开第四栏。选中引用携带节点 ID 和产物 revision，不发送整张图。

运行图状态更新按 `runId/taskId/agentInstanceId` 关联业务节点，不能仅用显示标签匹配。图中出现环不一律判错：解释图可有环，可执行工作流按后台允许的语义校验。

初始设计：常规节点约 200×72px，标题 14px，状态/编码 12px；用户连线交互在只读模式关闭。复用原流程画布的节点和边语义，但将编辑器行为与只读产物视图拆开。若后续加入本体图，新增 `graphSchema` 适配，不把本体的实体/关系节点直接套到工作流节点。

错误处理：重复 ID、孤立边端点、不支持节点类型、节点数超限均显示结构错误并允许查看安全的错误摘要；不自动执行模型建议的“修复代码”。

### 10.3 Table 渲染器

列定义包含 `id/title/dataType/nullable/format/permissions`，值类型区分 string、integerString、decimalString、boolean、date、datetime、link。时间和数值的展示规则固定，不把大整数转成 Number 造成精度丢失。

V1 统一使用服务端排序、过滤、分页。TanStack v8 配置 `manualPagination/manualSorting/manualFiltering`，不只对当前页做本地排序并显示为全表排序。[S9]

第一次应用筛选创建 querySnapshotId，后续分页、选中引用和导出复用同一快照。用户变更筛选/排序时创建新查询并回到首页，旧 querySnapshot 下的行选择清空或明确保留为旧引用。快照过期显示“数据已过期，请刷新”，不静默把旧选择绑定到新数据。

默认 pageSize=50，最大 200；只缓存有限页。列过多时水平滚动、固定标识列；可选行虚拟化，但虚拟化不替代服务端分页。未知总行数显示“已加载/还有更多”，不虚构精确总数。

表格类型区分 `snapshot` 和 `live`：snapshot 内容随 revision 固定；live 每次查询需显示“截至时间”和快照 ID。打开历史 live 产物只能说“实时查询视图”，不能称为当时固定数据。

支持选中行或单元格后“引用到对话”；空表、无权限列、加载错误均有独立状态。导出权限由服务端判断；CSV 的公式注入转义和大数据导出由服务端实现，前端只显示任务进度和授权下载。

### 10.4 Markdown 渲染器

使用与聊天正文相同的 SafeMarkdown。启用 GFM 表格、列表和代码块；默认跳过原始 HTML，不启用任意 `rehype-raw`。react-markdown 的安全默认值不能替代对自定义组件、插件和 URL 策略的审查。[S10]

外链只接受允许协议，设置 `rel=noopener noreferrer`；外部图片默认不自动加载，避免无意泄露访问元信息。已授权附件图片通过受控资源接口加载。代码块只有复制按钮，没有执行按钮。

文档内容包含服务端稳定 blockId 与源位置。第一版允许按段落/块引用，精确子串选择只作附加定位；不把渲染后 DOM offset 当成源文件 offset。代码块、表格跨多节点选择无法精确定位时降级为整块引用。

目录、代码高亮和大段渲染延迟计算；聊天流仅更新尾部未完成内容，已完成消息不随每个 delta 全量解析。未闭合代码块在生成过程中以容错预览显示，最终以完整消息重新校正。

### 10.5 Webpage 渲染器

三种正式状态：`internal`、`embed_allowed`、`external_only`。由后端/部署白名单决定可选预览策略，前端不根据模型一句“允许嵌入”改变策略。

网站可用 `X-Frame-Options` 或 CSP `frame-ancestors` 阻止嵌入；不能用更换 iframe 库或 CORS 参数解决。[S13] 同时，浏览器对 iframe 加载失败的检测并不可靠：不能把 onLoad 当作页面真正加载成功的证据。[S14]

始终提供“在新标签页打开”；预览等待超时只显示“未确认页面可用，请在新标签页打开”，不武断称为被 CSP 拦截。站内受控页面可通过 origin+source+nonce 验证的 postMessage 握手确认就绪，跨站通用页面不能假设有此能力。

iframe 采用最小 sandbox 权限、明确 referrerPolicy 和允许的 frame-src。远程内容不能取得平台会话凭据；不启用任意顶层导航、不允许模型随意申请麦克风或摄像头。对于需要 scripts/same-origin 的特殊站点单独评审，不能对同源不可信页面同时放宽后宣称已经隔离。

禁止用通用代理绕过网站嵌入限制。未来的生成式 HTML/React 应用必须使用独立来源的沙箱服务，按新的产物类型建设；不纳入当前普通网址渲染器。

### 10.6 产物修订与冲突

用户让 Agent 修改时，请求必须引用 baseRevision。后端修订工具以该版本做乐观并发校验；冲突生成可解释的错误或分支提案，不覆盖更高版本。

V1 的“比较版本”至少展示版本元信息和变更摘要；Markdown 可做文本 diff，图可展示新增/删除/修改节点与边列表，表格展示 schema/生成条件变化。精确全量大表逐行 diff 不作为 V1 前置能力。

---

## 11. 附件、引用、审批与能力入口

### 11.1 附件

上传状态：`selected → uploading → scanning → ready/rejected`。只有 ready 附件可以被正式发送；扫描失败、类型拒绝、网络重试必须明确反馈。前端 accept 只是提示，后端按实际内容类型和安全检测判断。

文件先得到受控 attachmentId 再关联消息；失败发送后允许用户重试同一 ready 附件，不反复上传。孤儿附件由服务端按保留策略清理。Blob URL 在移除、切换身份和页面卸载时 revoke。

预览与上传由不同权限控制；不将文件 base64 塞进长消息或 localStorage。缺少通用附件 API 的旧部署不拿知识库上传接口冒充聊天附件上传。

### 11.2 引用与证据

点击引用打开右栏的安全 Markdown/原文片段视图，展示文档标题、版本和位置。证据 ID 必须来自后端；不是所有蓝色链接都等同于已验证事实。

引用已删除或失权时，消息保留“引用不可用”的占位，但不缓存展示旧正文。后端判定用户可见的最终回答是否也需脱敏；前端不能通过删除链接来替代源内容撤权处理。

### 11.3 审批卡

卡片显示 operationId、工具与版本、目标资源、脱敏参数、argsHash 对应的摘要、风险、申请人、有效期、当前状态。前端仅提交决策，不修改实际参数；参数变化必须新建审批。

批准或拒绝后进入“提交中”，收到服务端新 revision 再更新；双击、多个标签页同时批准均由幂等和审批版本防护。过期、撤销和无审批权的卡片只读。

### 11.4 用户与能力设置

会话页显示当前 Agent 已绑定的 Tool/Skill/MCP/知识库概况，以及记忆启用状态；只显示用户有权知道的名称，不暴露连接凭据。用户可进入自己的记忆管理页查看来源、确认候选或遗忘；资源配置跳转现有管理入口，不在聊天输入框内拼管理命令。

Agent 或模型切换默认仅对新会话生效。现有会话绑定的发布版本不能因为用户切换下拉框而悄悄改变；需要切换时创建新会话，并由后端授权导入摘要。

---

## 12. 安全、权限与隐私

生产建议采用同源 Java 会话或既有 SSO 的 HttpOnly、Secure Cookie，写操作加 CSRF 与 Origin 检查；现有 Bearer 管理员 Token 只保留开发兼容入口，禁止把模型密钥放入浏览器。[S3 为当前实现依据；此处是目标设计]

若现有企业认证必须使用 Bearer，则由现有统一认证组件提供短期凭据，统一在 HttpClient 注入；不在 URL、埋点、console 或长期本地缓存保存。UI 不自行实现一套不受控的刷新 Token 流程。

| 边界 | 必须实现 |
|---|---|
| 对象访问 | 所有 Conversation/Run/Artifact/Attachment/Evidence 接口逐项鉴权，不能依赖隐藏按钮 |
| 内容执行 | 不用 eval、new Function、动态 import 远端 URL；不执行模型生成 JSX/HTML |
| Markdown 与链接 | 安全 URL 策略、禁原始 HTML、受控图片、插件审核 |
| iframe | 白名单、sandbox、独立来源策略、可验证 postMessage；永不转发平台 Token |
| 缓存 | 查询键含 scope；退出、失权、换用户清理；默认不把内容写 localStorage/IndexedDB |
| 日志 | 仅事件 ID、耗时、错误码和 traceId；正文、文件、工具参数默认不埋点 |
| 附件 | 类型/大小/扫描/权限均在后端复核；前端只帮助用户理解状态 |
| 下载 | 鉴权、短期授权、文件名规范、权限失效处理 |

隐私边界必须真实说明：服务端撤权后应尽快清理应用可控缓存，但不能保证清除用户已复制、截图或下载的数据。不要对前端撤权做不可兑现的承诺。

---

## 13. 性能、可访问性与运行指标

以下是首版建议验收预算，不是已有实测结果。M0 固定测试浏览器、硬件、窗口尺寸和网络配置后，记录真实数据；业务大模型首 Token 时间单独计量，不归罪于 UI。

| 场景 | 建议门槛 |
|---|---|
| 常规基准 | 桌面 Chromium、1440×900、4 核/8GB 级测试机、100ms 模拟 RTT |
| UI 点击反馈 | 100ms 内出现明确交互反馈，不包括网络完成 |
| 事件接收至可见 | 本地投影与渲染 P95 ≤100ms；文本合并更新约每 50ms 一次 |
| 会话历史 | 首屏 50 条，累计加载 2,000 条测试；禁止一次读取全部历史 |
| 长消息 | 50KB 文本流持续更新不让输入明显卡顿；超过预算转产物或分页查看 |
| 流程图 | 300 节点/600 边的验收数据；更大图提供简化、分区或全屏模式 |
| 表格 | 100,000 行服务端数据、20 列；浏览器单页 ≤200 行，有限页缓存 |
| Markdown | 500KB 作为大文档压力样例；高亮/目录延迟，必要时分段加载 |
| 首包 | 新增重型 renderer 必须懒加载；初始路由 JS gzip 目标 ≤450KB |
| 内存 | 连续切换会话/产物 100 次，订阅、Blob URL 和 detached DOM 不持续增长 |

不能为了长聊天直接给 assistant-ui 再套一层虚拟滚动而破坏消息测量/滚动锚点；先做分页和尾部增量优化，再在 M0 对锁定版本验证虚拟化能力。

可访问性：全部功能可通过键盘到达；分隔条有可访问名称、方向与尺寸；模态抽屉正确管理焦点；图提供节点列表的等价文本入口；表头使用语义 th；工具与错误状态有文字。文本增量不逐 token 向屏幕阅读器播报，按句/消息完成进行温和通知。

可观测数据包括连接数、重连原因、恢复次数、序列不匹配、渲染异常、重复事件去重次数、产物打开耗时和请求 traceId；默认不采集用户正文。

---

## 14. 现有工程迁移与部署

### 14.1 分支与改造顺序

基于已核实的 scaffold 提交创建新开发分支；正式执行前再读取目标分支 HEAD 和 diff，不能覆盖用户后续修改。本文不授权自动合并或修改远端仓库。

先将原 App.tsx 迁为 LegacyConsole，并用适配 props 保留原四个入口；共享请求函数抽到 HttpClient；引入路由。旧全局 CSS 逐项命名空间化，不通过正则粗暴重写 @media/@keyframes；新 CSS Modules 避免 `.sidebar/.panel/button` 全局碰撞。

随后加入新工作台与 feature flag `conversationWorkspaceEnabled`。旧运行仍通过 LegacyRunAdapter 查看；没有服务端迁移记录时不冒充新式聊天历史。

### 14.2 并行开发顺序

先签订 snapshot、events、request receipt 和 artifact revision 四个核心契约。前端用同契约 Mock 开发，后端实现真实投影；两边必须用同一套 fixtures 和错误码测试。

不要在 UI 完成后才讨论会话永久存储、权限和断线水位；这些是会话工作台基础，不是末期优化。

### 14.3 静态部署

Vite 构建结果可继续复制到 Spring Boot 静态目录或部署在同域网关后。SPA fallback 只处理前端页面路径；`/api/**`、错误状态与资源 404 不能回退为 index.html。

开发环境由 Vite 代理 `/api`；生产不把 Java API 地址硬编码进组件。开关与公开能力可在启动时读取配置；任何 `VITE_*` 变量都视为浏览器可见，不能放秘密。

静态 hash 资源长缓存；index 和运行时配置短缓存或不缓存；API/SSE 及含敏感正文的响应禁止公共缓存。V1 不启用缓存会话内容的 Service Worker。发布时保持至少一个旧前端静态资源版本可用，处理跨部署打开页面的 chunk 加载失败。

### 14.4 CI 与回滚

开发阶段新增并固定 `typecheck / lint / test:unit / test:integration / build / test:e2e` 脚本；CI 用 `npm ci` 后按顺序执行。生产端到端必须连测试 Java 后端，不能只跑 Mock。

通过 feature flag 关闭新入口即可回到 LegacyConsole；前端回滚不修改/删除后端 Conversation 或 Artifact。API 协议 v1 在灰度期间向后兼容；遇到新必需字段/事件版本不支持时提示刷新或升级，而不是渲染错误内容。

---

## 15. Mock、契约测试与验收数据

交付包提供 `contracts.ts` 和 JSON/NDJSON fixtures。它们是可供 MSW、组件测试和后端联调复用的数据，不是连接真实 AgentScope 的生产应用。

核心场景包含：正常多轮与产物、重复事件、缺失事件、内容 offset 冲突、同一 conversation 的旧 attempt、两个会话交错、审批过期、权限撤销、iframe 外链降级、大整数表格与旧版产物查看。

Mock 模式只在开发/测试构建显式启用；页面持续标识“模拟数据”，假模型能力不被保存成用户真实产物。不得在生产请求失败后静默回退 Mock。

测试分层：

| 层次 | 关注点 |
|---|---|
| 单元 | reducer、delta offset、scope key、URL 策略、引用构造、版本比较 |
| 组件 | 各种卡片状态、输入法、键盘、错误边界、未知类型、只读权限 |
| 契约集成 | OpenAPI/Schema、快照水位、幂等回执、事件链、附件状态、表格查询快照 |
| 浏览器 E2E | 正常流程、刷新重连、断网、换账号、三个尺寸、真实 Java API |

Playwright 以用户可见行为与可访问角色定位，使用自动等待断言；避免固定 sleep 掩盖竞态和脆弱 CSS 选择器。[S16]

---

## 16. 开发工作包与依赖

优先级：P0 为 V1.0 正常交付必需；P1 为完整内容联动及上线加固；P2 为后续编辑增强。下表工项均有实际交付文件/接口和验收目标，不按“页面做完”判断完成。前置依赖首先要求相应契约评审就绪，不代表前端必须等全部后端编码完成；可用同契约 Mock 并行开发。

共 **40 个工作包：8 个后端配套、32 个前端工作包**。

| 工项 | 优先级 | 开发内容 | 前置依赖 | 产物/完成条件 |
|---|---|---|---|---|
| BE-01 | P0 | 真实身份、权限与 UI capabilities | 后台认证方案 | /me、/ui-capabilities；开发单 Token 与生产身份分离 |
| BE-02 | P0 | Conversation 持久化和历史分页 | BE-01 | 目录、CRUD、固定发布入口、消息顺序与版本 |
| BE-03 | P0 | 消息请求幂等与回执 | BE-02 | 202、clientRequestId 对账、超时回执查询 |
| BE-04 | P0 | 会话公开投影、快照与 SSE | BE-02、BE-03 | 一致水位、cursor 链、回放、权限重置；不透传 SDK |
| BE-05 | P0 | 产物元信息、版本与授权读取 | BE-01、BE-04 | ArtifactRevision、撤权、下载、引用权限 |
| BE-06 | P1 | 表格查询快照与异步导出 | BE-05 | 服务端排序分页、querySnapshotId、导出回执 |
| BE-07 | P1 | 附件检测与证据读取 | BE-01、BE-05 | ready 状态、鉴权原文定位、孤儿附件清理 |
| BE-08 | P0 | 补问、审批、取消和修订契约 | BE-03、BE-04、BE-05 | 针对 ID 的命令、状态确认、baseRevision 冲突处理 |
| FE-01 | P0 | LegacyConsole、路由与旧 CSS 隔离 | 现有 frontend | 保留四个旧入口；深链刷新可用 |
| FE-02 | P0 | 依赖锁定与基础组件 | FE-01 | 精确锁文件、React18 refs、主题与 splitter 封装 |
| FE-03 | P0 | HttpClient、IdentityScope、错误标准 | BE-01 | 401/403、凭据注入、AbortSignal、scopeGeneration |
| FE-04 | P0 | 契约类型与运行时 schema | BE-02～BE-05 | API/事件/产物校验；与后端契约一致 |
| FE-05 | P0 | ConversationStore 与选择器 | FE-03、FE-04 | 单一消息投影、分页归并、临时消息对账 |
| FE-06 | P0 | 流读取与重连 supervisor | FE-03、FE-04、BE-04 | fetch SSE、UTF-8、心跳、限额、旧连接清理 |
| FE-07 | P0 | 事件 reducer 与恢复协调 | FE-05、FE-06 | cursor 链、重复去重、attempt/offset、快照重置 |
| FE-08 | P0 | 会话侧栏 | FE-01、FE-03、BE-02 | 搜索分页、置顶归档、重命名删除、用户区 |
| FE-09 | P0 | 新会话与首条消息事务对账 | FE-05、BE-03 | 空草稿不落库、稳定幂等键、超时不重复创建 |
| FE-10 | P0 | assistant-ui RuntimeAdapter | FE-02、FE-05、FE-09 | 发送/取消桥接；业务卡不伪装工具；禁未实现能力 |
| FE-11 | P0 | 统一安全 Markdown 与消息渲染 | FE-02、FE-10 | GFM、代码复制、增量尾部、无任意 HTML |
| FE-12 | P1 | 输入框、附件与引用标签 | FE-10、BE-07 | IME、ready 文件、可移除引用、限额提示 |
| FE-13 | P0 | 活动与工具回执展示 | FE-07、FE-10 | 状态文本、折叠进度、UNKNOWN/RECONCILING |
| FE-14 | P1 | 补问卡与答复 | FE-13、BE-08 | questionId/revision、重复防护、过期 |
| FE-15 | P1 | 审批卡 | FE-13、BE-08 | 操作目标、参数哈希、权限/有效期、决策回执 |
| FE-16 | P0 | 停止与错误恢复 UI | FE-07、FE-13、BE-08 | 断线≠取消，正在停止≠已停止，重试不重放写 |
| FE-17 | P0 | 三栏、标签和深链接 | FE-01、FE-02 | 宽度记忆、最大化、焦点返回、版本标签键 |
| FE-18 | P0 | 产物注册表与错误边界 | FE-04、FE-17、BE-05 | lazy renderer、未知类型、单栏错误、不调用模型 |
| FE-19 | P0 | React Flow 只读产物 | FE-18 | 图校验、受控节点边、purpose 区分、布局恢复 |
| FE-20 | P1 | 图节点详情与选中引用 | FE-19 | 节点 ID/版本引用，运行图结构不可改 |
| FE-21 | P0 | 数据表基本查看 | FE-18、BE-05 | 类型列、空表/错误、分页接口占位契约 |
| FE-22 | P1 | 表格查询、选择和导出 | FE-21、BE-06 | 全局排序过滤、快照绑定、精度与导出权限 |
| FE-23 | P0 | Markdown 产物与块定位 | FE-11、FE-18 | 目录、安全链接、blockId、下载 |
| FE-24 | P0 | 网址与外链降级 | FE-18 | internal/embed_allowed/external_only，最小 iframe 权限 |
| FE-25 | P1 | 统一 ContextReferenceService | FE-12、FE-20、FE-22、FE-23 | 验证选择、上限、草稿标签、服务端复验 |
| FE-26 | P1 | 修订提醒与版本比较 | FE-18、FE-25、BE-08 | 旧版稳定、新版提示、baseRevision 冲突 |
| FE-27 | P0 | 失权和跨标签页缓存清理 | FE-03、FE-05、FE-18 | 退出/换用户/失权清理，revoke Blob URL |
| FE-28 | P1 | 响应式与可访问性 | FE-08、FE-10、FE-17～FE-24 | 三档视图、200%缩放、键盘、焦点、文本替代 |
| FE-29 | P1 | 性能优化与前端观测 | FE-07、FE-18～FE-24 | 懒加载、事件合批、长会话、图表压力、无敏感埋点 |
| FE-30 | P0 | Mock 与契约/组件测试基础 | FE-04 | fixtures、MSW、断线/重复/错版本/权限案例 |
| FE-31 | P1 | 真实 Java E2E 和旧入口回归 | M1～M3、FE-30 | 全部验收记录；Mock 与真实结果分开 |
| FE-32 | P1 | 部署、灰度和交付文档 | FE-29、FE-31 | SPA fallback、SSE 网关、依赖扫描、回滚演练 |

### 16.1 里程碑

| 里程碑 | 交付 | 放行条件 |
|---|---|---|
| M0 契约和工程基线 | 锁版本、迁移旧入口、schema、fixtures | React 18 组件兼容、旧入口不退化、核心协议可签字 |
| M1 会话闭环 | 身份、历史、输入、快照、SSE、幂等、取消 | 刷新/断线不重复执行，不串会话，旧新接口明确分离 |
| M2 内容工作区 | 四类 renderer、产物卡、权限与版本 | 四类可打开，网址能降级，旧版本不会被覆盖 |
| M3 内容联动 | 选择引用、Agent 修订、审批与证据 | 操作对象可定位，审批不越权，版本冲突可处理 |
| M4 上线验收 | 性能、安全、灰度、可观测性、回归 | 全部 P0/P1 用例有证据，真实后端 E2E 通过 |

建议至少明确三种职责：前端负责人负责投影/组件，后端负责人负责权威协议与执行语义，测试负责人负责真实环境和故障注入；同一人可兼任，但每个工项只能有一个最终负责人。工期需由实际人数与后端完成度评估，本文件不把没有估算依据的天数当作交付承诺。

---

## 17. 验收清单

每条验收记录保存：用例 ID、构建提交、后端提交、浏览器、数据集、结果、日志/截图或请求回执。下面“应当”描述目标行为，当前不代表已通过。

共 **60 个验收场景**。

| 用例 | 分类 | 验收目标 | 层次 |
|---|---|---|---|
| AC-01 | 会话 | 首次新建未发送不创建空数据库会话；首次发送只产生一个 Conversation 和一个请求。 | 集成/E2E |
| AC-02 | 会话 | 创建已成功而发送失败时重试沿用 conversationId，不制造重复会话。 | 集成 |
| AC-03 | 会话 | 刷新后恢复同一会话、持久消息和当前运行，不把它当作新任务。 | E2E |
| AC-04 | 会话 | 搜索命中未加载历史页中的会话，并处理快慢请求返回顺序。 | 集成/E2E |
| AC-05 | 会话 | 连续加载历史页没有重复或时间排序抖动，消息锚点保持。 | 组件/E2E |
| AC-06 | 会话 | 两个客户端并发重命名产生明确版本冲突，不静默覆盖。 | 集成 |
| AC-07 | 会话 | 正在运行的会话不能直接删除；归档不会取消后台任务。 | 集成/E2E |
| AC-08 | 会话 | 置顶、归档和标题在刷新后保持；删除按后端实际保留策略提示。 | E2E |
| AC-09 | 会话 | 中文输入法组合期间 Enter 不误发，Shift+Enter 能换行。 | 组件/E2E |
| AC-10 | 会话 | 查看历史时新消息不抢滚动位置，回到底部入口可用。 | 组件/E2E |
| AC-11 | 会话 | 原 Agent、工作流、运行调试、资源四个入口全部可用。 | E2E |
| AC-12 | 会话 | 生产显示真实身份；旧单 Token 模式明确显示本地管理员，不假装多用户。 | E2E |
| AC-13 | 协议 | SSE 先于 POST 202 到达时用户消息只有一份。 | 契约集成 |
| AC-14 | 协议 | POST 超时后通过原 clientRequestId 找回回执，不再次执行同一输入。 | 契约集成/E2E |
| AC-15 | 协议 | 相同 eventId 和重复文本 delta 不重复追加。 | 单元/集成 |
| AC-16 | 协议 | previousCursor 断链触发快照恢复，不直接跳到最新事件。 | 单元/集成 |
| AC-17 | 协议 | 旧 attempt 的文本不能接入新 attempt；完整消息可校正草稿。 | 单元/集成 |
| AC-18 | 协议 | 含中文、Emoji 的 UTF-16 offset 在 Java 与浏览器间保持一致。 | 单元/契约集成 |
| AC-19 | 协议 | UTF-8 跨 chunk、多行 data、CRLF、注释心跳与截断事件均正确处理。 | 协议集成 |
| AC-20 | 协议 | 401、429 和代理断线遵循不同恢复策略，不产生无限刷新或重连风暴。 | 集成/E2E |
| AC-21 | 协议 | 会话 A 的旧连接在切到 B 后不能污染 B 的消息或产物。 | 集成/E2E |
| AC-22 | 协议 | SSE EOF、页面关闭、网络取消都不被误显示为业务成功或已取消。 | 集成/E2E |
| AC-23 | 协议 | 取消从请求到确认有独立状态；已经成功的工具不被假装撤销。 | 集成/E2E |
| AC-24 | 协议 | cursor 过期、权限版本变化、未知必需协议版本有明确快照/升级处理。 | 契约集成 |
| AC-25 | 产物 | 卡片打开/关闭/切换/最大化不产生额外 LLM 请求。 | 集成/E2E |
| AC-26 | 产物 | 深链接读取指定产物 revision，并重新执行对象授权。 | 集成/E2E |
| AC-27 | 产物 | 新版本生成时老消息和老标签仍指向旧版本，用户主动切换新版。 | 单元/E2E |
| AC-28 | 产物 | 未知 kind 或 renderer 抛错只影响右栏，聊天输入仍可用。 | 组件/E2E |
| AC-29 | 产物 | 产物失权后清理正文、选中引用和缓存；不保留可见旧副本。 | 集成/E2E |
| AC-30 | 产物 | 图的重复 ID、无效边端点和不支持类型被拒绝，不执行远端代码。 | 单元/组件 |
| AC-31 | 产物 | run_projection 不允许结构编辑，执行状态从业务 ID 正确映射。 | 集成/E2E |
| AC-32 | 产物 | 面板缩放和流式进度不反复 fitView，返回标签保持视口。 | 组件/E2E |
| AC-33 | 产物 | 表格排序和筛选作用于服务端完整查询，不只作用于当前页。 | 契约集成/E2E |
| AC-34 | 产物 | 9007199254740993 等大整数和高精度小数显示、引用不丢精度。 | 单元/集成 |
| AC-35 | 产物 | querySnapshot 过期明确提示；不把旧选择默默映射到新数据。 | 集成/E2E |
| AC-36 | 产物 | 更改过滤或排序后选择状态按规则清理；导出和引用对应同一快照。 | 集成/E2E |
| AC-37 | 产物 | Markdown 原始 HTML、危险 URL 和可执行代码不能运行。 | 安全/组件 |
| AC-38 | 产物 | 证据跳转正确定位文档版本与块；缺失或失权显示占位。 | 集成/E2E |
| AC-39 | 产物 | 禁止嵌入的网站有外链入口；onLoad 不被当作成功加载的唯一判断。 | 浏览器E2E |
| AC-40 | 产物 | 外部图片默认不自动加载；安全协议外链正常，危险协议拒绝。 | 安全/组件 |
| AC-41 | 联动 | 选择图节点后发送确切 artifactId/revision/nodeIds。 | 组件/集成 |
| AC-42 | 联动 | 表格引用包含 rowIds、columnIds 和 querySnapshotId，后端重新鉴权。 | 契约集成 |
| AC-43 | 联动 | Markdown 引用使用 blockId，渲染 DOM 偏移不冒充源文件偏移。 | 单元/组件 |
| AC-44 | 联动 | 用户可以删除引用标签，删除后的对象不进入发送上下文。 | 组件/E2E |
| AC-45 | 联动 | 基于旧 revision 请求修改产生冲突/新分支，不覆盖用户新版本。 | 契约集成/E2E |
| AC-46 | 联动 | 上传扫描未完成或被拒绝的附件不能发送为可读内容。 | 集成/E2E |
| AC-47 | 联动 | 消息失败重试不重复上传 ready 附件；移除附件释放 Blob URL。 | 组件/集成 |
| AC-48 | 联动 | 补问按 questionId/revision 回答；过期答复不进入错误任务。 | 集成/E2E |
| AC-49 | 联动 | 双标签重复审批只生效一次；参数哈希改变需要新审批。 | 契约集成/E2E |
| AC-50 | 联动 | 工具 UNKNOWN/RECONCILING 不显示成功，批准操作不等于实际执行成功。 | 组件/集成 |
| AC-51 | 安全 | 退出、换账号和跨标签退出通知清理所有作用域缓存与连接。 | 集成/E2E |
| AC-52 | 安全 | 猜测其他用户的会话、产物、附件或 clientRequestId 无法越权读取。 | 安全集成 |
| AC-53 | 安全 | 日志、埋点、URL、页面源码不包含模型密钥、Token 或默认消息正文。 | 安全/静态检查 |
| AC-54 | 安全 | iframe、postMessage、链接和未知 renderer 不能取得主应用凭据或执行任意代码。 | 安全/E2E |
| AC-55 | 安全 | Cookie 认证写操作有 CSRF/Origin 防护；Bearer 模式无查询串 Token。 | 安全集成 |
| AC-56 | 可用性 | 键盘完成三栏操作，焦点可恢复；屏幕阅读器不逐 token 播报。 | 无障碍/人工 |
| AC-57 | 可用性 | 360、900、1280、1440 像素与 200% 缩放下关键操作不被遮挡。 | 浏览器E2E |
| AC-58 | 性能 | 切换会话/产物 100 次没有持续增长的订阅、Blob URL 和 detached DOM。 | 浏览器性能 |
| AC-59 | 性能 | 长会话、大图、10万行服务端表格和大 Markdown 按固定环境测得预算结果。 | 性能测试 |
| AC-60 | 发布 | 真实 Java E2E、旧入口回归、SSE 网关、SPA 深链、灰度与回滚均有证据；不以 Mock 冒充。 | 发布验收 |

### 17.1 第一条端到端验收脚本

使用独立测试账号 A 创建会话，选择已发布入口，发送“分析流程并生成流程图、数据表和报告”。消息流出现卡片，分别打开三类产物；点击一个允许的地址，再测试一个只能外链的网址。选择图中两个节点引用到输入框，请 Agent 改成并行，生成 v2。回看第一条消息仍打开 v1。

执行期间人为断开 SSE，随后恢复；断线只恢复事件、不重复发送用户消息。写操作到达审批时刷新页面，批准后显示工具真实回执。再次刷新恢复同一会话。切换账号 B 后，用深链接访问 A 的产物必须被拒绝；回到 A 后仍按有效权限访问。

最后在窄屏、键盘导航和 200% 缩放下完成打开/关闭内容面板、答复补问与批准操作。该脚本不能替代其他异常用例，但作为每次合并的核心回归链。

---

## 18. 交付完成定义与工程约束

前端交付不是几张静态页面。完整交付须同时包含：可运行代码及精确锁文件、平台契约与后端实现说明、组件/协议/E2E 测试、运行配置示例、静态部署和 SSE 代理说明、灰度开关、回滚方法、许可证与漏洞扫描结果。

AI 编程代理落地时遵循以下次序：

```text
读取仓库目标提交与增量
  → 保留旧入口并加最小回归
  → 确定前后端契约与锁定依赖
  → 统一身份、请求层、投影 reducer
  → 实现 assistant-ui adapter
  → 新建/历史/发送/恢复闭环
  → 产物协议与四类 renderer
  → 引用、审批、版本冲突
  → 真实后端、安全与性能验收
```

禁止的捷径：在 JSX 里写 fetch 和业务状态机；以 localStorage 充当永久会话库；每个 renderer 自己连 SSE；根据 Markdown 内容猜执行指令；用任意 React/HTML 代码作为流程图；将 UI 隐藏当权限；在失败时自动生成新的幂等键重试写操作；用中止网络连接冒充取消任务；把示例/Mock 测试结果写成真实后端验收通过。

---

## 19. 来源与核实范围

[B1] 本会话已交付文件《AgentScope_Java_AgentHarness_开发落地方案_V1.0.md》。本方案读取并沿用其架构、领域对象、产物和权限边界；它本身也是开发设计，不是已实现事实。

[S1] Project_scarlet PR #1 元数据及交付说明；读取时 open、未合并。`https://github.com/NatsumeWeiXia/Project_scarlet/pull/1`

[S2] 固定提交的 frontend/package.json。`https://github.com/NatsumeWeiXia/Project_scarlet/blob/e100f0fa0e46b625ff4222f0af0a428b9a8b4b2b/frontend/package.json`

[S3] 固定提交的 frontend/src/App.tsx。核实入口、请求方式、轮询和 Token 存储，不宣称全面审计全部组件。`https://github.com/NatsumeWeiXia/Project_scarlet/blob/e100f0fa0e46b625ff4222f0af0a428b9a8b4b2b/frontend/src/App.tsx`

[S4] 固定提交的 src/main/java/io/scarlet/app/Api.java。核实现有端点和响应形态。`https://github.com/NatsumeWeiXia/Project_scarlet/blob/e100f0fa0e46b625ff4222f0af0a428b9a8b4b2b/src/main/java/io/scarlet/app/Api.java`

[S5] assistant-ui React compatibility。`https://www.assistant-ui.com/docs/migrations/react-compatibility`

[S6] assistant-ui ExternalStoreRuntime。`https://www.assistant-ui.com/docs/runtimes/custom/external-store`

[S7] react-resizable-panels 官方 README。`https://github.com/bvaughn/react-resizable-panels`

[S8] React Flow Save and Restore。`https://reactflow.dev/examples/interaction/save-and-restore`

[S9] TanStack Table v8 Pagination Guide。`https://tanstack.com/table/v8/docs/guide/pagination`

[S10] react-markdown 官方 README 和安全说明。`https://github.com/remarkjs/react-markdown`

[S11] eventsource-parser 官方 README。`https://github.com/rexxars/eventsource-parser`

[S12] MDN EventSource 构造器和 SSE 指南。`https://developer.mozilla.org/en-US/docs/Web/API/EventSource/EventSource`；`https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events`

[S13] MDN CSP frame-ancestors。`https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/frame-ancestors`

[S14] MDN iframe：sandbox、加载事件与安全。`https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/iframe`

[S15] Vite 7 Node.js 支持说明。`https://main.vite.dev/blog/announcing-vite7`

[S16] Playwright Best Practices。`https://playwright.dev/docs/best-practices`

以上公开文档在本次编制时核对；库的主版本 API、peerDependencies 和安全补丁仍以 M0 实际安装并锁定的发布包为准。全部平台数据格式、默认阈值和任务拆分为本次原创实施设计。
