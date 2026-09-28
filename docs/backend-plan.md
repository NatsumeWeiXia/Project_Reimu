# AgentScope Java 通用 Agent Harness 开发落地方案

**版本：V1.0｜编制日期：2026-09-24｜适用技术栈：Java 17 / Spring Boot / AgentScope Java；Java 8 业务系统通过服务接口连接**

本文是开发设计与任务拆解，不是已经实现或测试通过的产品说明。文中的平台类名、接口、配置字段、表名和验收指标是建议设计；AgentScope 的真实接口以锁定版本的发布制品、源码及契约测试为准。事实依据见文中 [Sx] 和末尾来源。

## 0. 当前基线与本次工作的定位

已读取 Project_scarlet 的 PR #1 元数据、固定提交 `e100f0fa0e46b625ff4222f0af0a428b9a8b4b2b` 的 README、架构说明及 `AgentRunner.java`。PR 在本次读取时未合并；不要把分支实现当成 main 已有能力。[S1]

仓库说明的现状是：Java 17 + Spring Boot 3.5.5；已有 Agent/工作流定义、交接、委派、问题回复及持久化骨架，但尚未接入 AgentScope；采用单 Worker 和 Run 单行 JSON 聚合；长期记忆、自动摘要、细粒度权限、写审批、多 Worker 接管等尚未完成。这里是对仓库说明和已读代码的核对，不是重新执行其 CI 或对全部代码进行审计。[S2][S3]

当前 SPI 是 `Action next(Input input) throws Exception`，不暴露 SDK 类型。[S4]

本方案不是推倒重做协作系统，而是：

> 保留业务协作状态机 → 升级 AgentRunner 契约 → 接入 AgentScope Harness → 建设平台能力管理 → 补齐生产恢复、安全和测试。

AgentScope Java 官方文档要求 JDK 17+，以 `agentscope-harness` 作为 HarnessAgent 入口；发布记录列有 2.0.1。以下按 2.x 能力体系设计，但不假定滚动文档中的每个接口都属于同一个发布制品。开工时必须锁定 SDK、模型扩展、MCP SDK 和 Spring Boot 的实际版本。[S5][S6]

---

## 1. 目标、完成标准与边界

### 1.1 要交付的最终能力

平台管理员能够创建并发布 Agent，配置模型、上下文预算、记忆策略、工具、Skill、MCP、知识库和执行权限。用户能够多轮交流、补充材料、回答问题、审批操作、取消任务、查看结果与证据。运行时能够在受控边界保存现场，在进程重启后继续处理，并与已有串行、并行、交接、委派规则保持一致。

“完整”包括配置、执行、持久化、安全、运维和验收，不只是六类能力各有一个查询接口。

### 1.2 两个交付门槛

**功能闭环版：** 六项核心能力均可配置和使用；多轮会话与暂停恢复可用；所有副作用工具有统一权限、审批、操作账本；完成既有协作回归。可保持单 Worker，但必须明确约束并强制防止误开多 Worker。

**生产受控版：** 在功能闭环基础上，完成多租户/多用户边界、细粒度撤权、分布式抢占和旧 Worker 隔离、备份恢复、安全测试、观测与容量验证。未通过这一门槛不能标称多 Worker 高可用或生产安全已验收。

### 1.3 本版不做的事情

不开发新的 LLM 推理算法；不重写 AgentScope 的 ReAct、流式解析、基础工具协议或 MCP 协议；不把 Pi 加入主链路；不引入第二套业务任务调度器；不承诺任意外部写操作 exactly-once；不以“模型说完成”替代业务验证。任意脚本和任意本地 MCP 进程不能在未隔离的主应用中开放。

---

## 2. 总体架构与职责划分

```text
React 配置工作台 / 会话工作台 / 运行调试 / 审批中心
                          │ REST + SSE
                          ▼
Java 17 + Spring Boot：平台应用层
  Agent 发布、会话、任务、身份权限、审批、配额、审计
                          │
                          ▼
现有协作领域层与调度器
  Task / AgentInstance / owner / handoff / delegate / join
  ExecutionTurn / Inbox / Outbox / Job / lease / fence
                          │ 平台 AgentRunner SPI
                          ▼
AgentScope 适配层
  AgentDefinitionCompiler / HarnessFactory / EventMapper
  ContextAssembler / StateStoreAdapter / Middleware
                          │
                          ▼
AgentScope Java HarnessAgent
  单 Agent 推理循环、模型适配、Skill 加载、上下文压缩
                          │ 所有能力经过同一授权边界
                          ▼
ToolGateway / MemoryService / KnowledgeService / ArtifactService
  Java Tool / HTTP Tool / MCP Client / 受控脚本执行器
                          │
                          ▼
Java 8 业务服务 / 知识库 / 数据库 / 外部 MCP / 隔离沙箱

存储：PostgreSQL（权威状态）+ 对象存储（大文件）
可选：pgvector（本地向量检索）、Redis（缓存和限流）
```

### 2.1 三个明确边界

| 层次 | 拥有的职责 | 不拥有的职责 |
|---|---|---|
| 协作层 | 谁负责、谁可运行、创建子任务、交接、合并、暂停、取消 | Agent 每一步如何推理 |
| AgentScope 运行时 | 当前 Agent 如何使用上下文和工具推进一个执行片段 | 修改业务 owner、绕过平台创建隐藏业务子任务 |
| 平台能力层 | 权限、凭据、工具执行账本、知识授权、记忆治理、产物审计 | 再造一个模型推理循环 |

Harness 已提供工作空间、状态存储、记忆、压缩、Skill、子 Agent 等工程机制；本方案选择性复用，不全量打开默认功能。[S7]

### 2.2 部署与依赖规则

初期采用模块化单体：一个 Spring Boot 应用按包隔离，API 与 Worker 可先同进程。面向生产允许同一代码制品以 `api`、`worker`、`maintenance` 三种 profile 部署。数据库独立；沙箱/脚本执行器独立进程或容器。

Java 8 服务通过 HTTP/JSON 等已明确版本的接口对接，不依赖 Java 17 的运行时 JAR。跨服务必须有服务身份、超时、幂等、traceId。Java 8 本身不要求重写，但应单独验证旧服务兼容性。

不为本项目同时引入第二套会话引擎、记忆引擎和工作流控制面。Redis 不作为与 PostgreSQL 并列的业务真相源；Redis 丢失不应丢任务、审批和操作回执。

---

## 3. 核心领域对象与状态语义

### 3.1 对象定义

| 对象 | 含义 | 关键约束 |
|---|---|---|
| AgentDefinition | 可编辑的 Agent 定义 | 草稿可变，已发布版本不可变 |
| AgentVersion | 一次发布快照 | 固定 Prompt、能力绑定、模型配置引用、策略版本 |
| Conversation | 用户看到的聊天容器 | 不直接作为所有 Agent 的 SDK sessionId |
| Run | 一次业务目标的运行容器 | 固定入口和发布清单；可包含多次用户消息 |
| Task | 有负责人和完成条件的业务工作 | 同一时刻最多一个当前负责人 |
| AgentInstance | 某 Task/节点对应的独立 Agent 执行上下文 | 交回时恢复原实例，不重新创建一份记忆 |
| Request | 用户本次输入或审批/补问答复 | clientRequestId 幂等 |
| ExecutionTurn | 一次有界 SDK 调用片段 | 可含多次模型推理；不等于整个工作流 |
| ToolInvocation | 一次已提交的工具操作意图 | opId 跨重试稳定，不直接用新模型生成 ID 去重 |
| Checkpoint | 可恢复的 SDK 状态快照 | 含版本、格式、哈希、代次、已消费输入游标 |
| Inbox / Outbox | 接收和投递业务事件 | 收发至少一次，消费幂等 |

直接聊天模式下，同一逻辑会话中的主 AgentInstance 连续处理多个 Request/Turn；回复完成后可进入 IDLE，下一条消息唤醒。自然语言答完一轮，不等于 Run 已终结。工作流模式下，Task 完成须满足显式输出契约。用户明确发起新业务目标时创建新 Run，旧上下文只能通过授权摘要导入，不能隐式共享所有 Agent 的完整历史。

### 3.2 状态机

```text
ExecutionTurn:
QUEUED → RUNNING → COMMITTED
                   ├─ FINAL_REPLY / TASK_OUTCOME
                   ├─ WAIT_EXTERNAL / WAIT_APPROVAL / WAIT_USER
                   ├─ YIELD_HANDOFF / YIELD_DELEGATE
                   └─ BUDGET_PAUSED
          └─ ABORTED / FAILED

AgentInstance:
READY → RUNNING → IDLE / WAITING / SUSPENDED / COMPLETED
                 └─ CANCEL_REQUESTED → CANCELLED（确认后）

ToolInvocation:
PREPARED → APPROVAL_PENDING → READY → DISPATCHING
                                         ├─ SUCCEEDED
                                         ├─ FAILED_RETRYABLE
                                         ├─ FAILED_FINAL
                                         └─ UNKNOWN → RECONCILING → 已核实结果 / MANUAL_REVIEW
```

WAITING 的原因单独记录，例如用户补问、审批、子任务等待和外部操作；不要把所有状态塞进一个不可解释的字符串。

取消是请求，不是已经撤销外部动作。工具超时也不等于未执行。已经完成的副作用需要另外的补偿操作，不能靠重置 Run 状态回滚。

### 3.3 身份与 SDK 状态映射

平台对每次执行从认证上下文解析 `tenantId / workspaceId / principalId`，模型输入不得覆盖。服务端生成实例 ID。

```text
平台逻辑键：
(tenantId, workspaceId, agentVersionId, executionPrincipalId, agentInstanceId)

SDK userId：经过编码的租户/工作空间/执行主体命名空间
SDK sessionId：agentInstanceId
StateStore key：命名空间 + SDK agentId + sessionId
```

AgentScope 通过 RuntimeContext 的 userId/sessionId 定位状态，但这不是身份认证或对象授权本身。[S8] 状态存储、工作空间、缓存、记忆检索、MCP 凭据池和产物下载均需落实同一隔离规则。

---

## 4. AgentScope 接入与执行契约

### 4.1 优先复用与平台补充

| 需求 | 复用方向 | 平台开发内容 |
|---|---|---|
| 推理、模型与流式响应 | HarnessAgent、模型扩展、事件流 | 模型配置、能力测试、预算、错误映射 |
| 会话状态 | AgentState / AgentStateStore | 平台命名空间、暂存/提交、版本迁移、恢复协调 |
| 上下文压缩 | 官方压缩组件 | token 预算、保留规则、撤权后重建 |
| Tool | Toolkit / ToolBase | 目录、参数验证、网关、审批、幂等、回执 |
| Skill | AgentSkillRepository 与加载机制 | 包校验、发布审核、版本锁定、依赖权限 |
| MCP | 官方客户端及工具发现 | 连接池、凭据、工具审核、协议兼容、网络边界 |
| 记忆 | 摘要机制可复用 | 企业模式 MemoryService、来源、确认、删除、失效 |
| 知识库 | 统一检索适配 | 数据授权、索引管理、引用与证据检查 |
| 可观测性 | OpenTelemetry 中间件 | trace 关联、指标、费用台账、脱敏 |

构建 Harness 后枚举实际可调用工具，与已编译 Manifest 做差集检查；存在未批准的 Shell、文件写入、动态注册或默认记忆工具时拒绝上线。原生工具、动态工具组和 Skill 激活出来的工具都不能成为旁路。

MCP 在平台采用网关代理方式：McpManager 持有真实客户端，向 SDK 暴露经过审核的 ToolBase/工具 schema 代理。不要同时把原始 MCP 客户端直接注册为另一组可绕过网关的工具。

### 4.2 升级 AgentRunner，而不是改变 Engine 的职责

旧 SPI 每次输出一个 Action。新增 V2 契约输出一个执行片段的结果，其中包含暂停原因、外部工具请求、暂存快照引用、用量和业务输出。旧 DemoRunner/JSON 动作 Runner 通过兼容适配器保留；旧 Run 固定旧 runtimeKind，不在运行中切换。

以下是平台接口草案，不是 AgentScope 原生 API：

```java
public interface AgentRunnerV2 {
    CompletionStage<SliceOutcome> advance(
        SliceRequest request,
        PlatformEventSink eventSink
    );
    CompletionStage<CancelAck> requestCancel(ExecutionKey key);
}

// SliceRequest：namespace、runId、taskId、instanceId、turnId、
// expectedStateVersion、fencingToken、输入信封、发布清单、预算。
// SliceOutcome：stopReason、stagedCheckpointRef、externalRequests、
// outputEnvelope、usage、lastConsumedInboxSeq。
// PlatformEventSink 只接收平台事件，不把 AgentScope 类型传播到领域层。
```

M0 必须将草案固化成可编译 DTO、枚举、JSON Schema 与错误契约。领域层只依赖平台 DTO；Reactor/AgentScope 依赖留在适配层。同步旧代码可以在有界 Worker 线程桥接异步，不在事件循环线程阻塞，不持数据库事务等待模型。

### 4.3 以外部工具边界衔接持久化

AgentScope 提供 `isExternalTool()`、`RequireExternalExecutionEvent` 和匹配的 ToolResultBlock 回填流程。[S9] 本方案将所有业务写工具、耗时操作、协作控制工具定义为平台托管的外部执行工具；纯本地无副作用的 Skill 读取等可以在 SDK 内执行，但仍受统一授权。

```text
SDK 产生工具请求
  → 只收集请求，不在事件回调内立即执行副作用
  → SDK 在受控边界结束当前 call
  → StateStoreAdapter 暂存本轮快照
  → 平台事务提交快照指针、操作意图、事件与 outbox
  → 独立 ToolDispatcher 执行真实操作
  → 保存操作结果并投递 Inbox
  → 下一 ExecutionTurn 回填同一 toolCallId/replyId 的结果
```

此设计不会重写 ReAct，只把业务副作用放在平台可控制的持久化边界之外。外部执行不会经过 SDK 的 onActing，因此 ToolGateway 自身必须再次执行完整权限与审计，不能只依赖 SDK middleware。[S10]

### 4.4 生命周期扩展点

建议实现 IdentityContext、CapabilityFilter、ContextBudget、EvidenceContext、ModelBudget、Trace、OutputValidation、Cancellation 等适配中间件。它们共享本轮 RuntimeContext，不把用户身份或可变状态存到复用中间件的实例字段中。

AgentScope 有 onAgent/onReasoning/onActing/onModelCall/onSystemPrompt 等扩展位置。[S10] 具体顺序以目标版本测试结果为准，尤其不能因为文档写了“中间件先运行”就假定任何内置工具都不可绕过。

### 4.5 协作工具

| 工具 | 业务动作 | 返回与恢复 |
|---|---|---|
| ask_user | 持久化 Question | 返回时绑定 questionId；等待不占线程 |
| handoff | 同一 Task 切换 owner，源实例暂停 | 目标使用自己的实例；交回恢复源实例 |
| delegate_wait | 创建独立子 Task，父任务等待 | 子任务达到 join 条件后再调度 |
| delegate_continue | 创建独立子 Task，立即回执 taskId | 父子分别排队，父任务不等待子结果 |
| finish_task | 提交结构化产物与完成证据 | Engine 验证完成条件，不凭文本直接成功 |

子结果发给父 Task 的当前负责人，不直接唤醒历史发起者。业务结果投递和 SDK 待决调用闭合是两件事：前者按当前 owner 路由；后者记录原 toolCallId 的结果回执，在原实例重新获得运行权后进行匹配回填。两者都由 taskId/commandId 去重。

父任务结束但仍有必须等待的子任务时，默认拒绝完成；确需脱离的任务必须显式标为 detached，并有独立完成通知和审计。取消后的迟到结果保留回执但不再触发新任务。

每个模型返回的工具批次必须完整记录、每个 toolCallId 最终有配对结果。若同一批同时包含 handoff 与写业务操作，第一版拒绝该混合批并返回结构化约束错误，不静默丢弃其中一项或让写操作抢先执行。

---

## 5. 一致性、检查点与崩溃恢复

### 5.1 双层状态，不是双重真相

PostgreSQL 中的 Task/owner/审批/取消/操作回执是业务权威。AgentState 是模型上下文与 SDK 待决调用现场。二者关联，但不得相互替代。

AgentScope 文档说明 StateStore 通常在 call 结束时保存，而不是每条消息都保存。[S8] 因此“支持 session persistence”不能直接推导出“任意时刻崩溃都无损恢复”。

### 5.2 推荐提交协议

1. 短事务领取实例，生成 turnId，记录当前 stateVersion 和 fencingToken。
2. 事务外读取最新已提交快照、已授权发布清单和 Inbox；重新计算当前权限。
3. 执行有界 SDK call。流式文本可以暂时展示，但标记为本轮未提交内容。
4. StateStoreAdapter 的 save 写入 STAGED 快照；在平台确认之前不作为可恢复头部。
5. 一个短事务校验 fence、版本和运行状态，将 checkpoint 标记 COMMITTED，并原子更新实例、待执行工具/协作命令、输入消费游标、事件和 Outbox。
6. 事务提交后才允许真实副作用执行。
7. 提交失败时丢弃本次 SDK 内存态/缓存，从最近 COMMITTED 状态恢复；过期 STAGED 快照由清理任务回收。

所有快照写入，包括清空上下文、取消及关机相关路径，都必须校验有效执行代次；管理操作应进入同一受控命令流程，不能在无租约的清理线程中覆盖正在执行的状态。

需要实际开发自定义 StateStore 适配器和提交协调器。这不是 AgentScope 自动提供的业务事务能力。M0 必须验证 save 时机、流结束时机、异常/取消路径和缓存清理接口；未验证前不能把外部请求“边流边执行”。

### 5.3 多 Worker 防重

使用数据库作业领取、租约、单调增加的 fencingToken 和乐观版本。心跳维持租约，但有效性必须在状态提交和 ToolGateway 派发前再次检查。不能仅依靠 JVM 锁或 SDK 的同会话串行。

fencingToken 能阻止旧 Worker 覆盖平台状态；不能自动阻止它已经发往外部系统的请求。副作用还须下游支持幂等键或可查操作状态；无法保证的操作进入 UNKNOWN/人工核对路径。

### 5.4 恢复矩阵

| 故障窗口 | 恢复方式 | 明确代价/限制 |
|---|---|---|
| 模型流中断，尚未提交快照 | 从最后提交点重跑，丢弃未提交文本 | 可能重复模型计费 |
| 暂存快照后、业务提交前崩溃 | 不执行外部动作，清理 STAGED，重跑 | 不把孤立快照当最新状态 |
| 意图已提交、尚未发送 | 使用相同 opId 重派发 | 请求参数不能改变 |
| 工具成功、回执保存前崩溃 | 按 opId 查询下游；支持时同键重试 | 不支持查询/幂等则 UNKNOWN |
| 回执已保存、SDK 尚未消费 | 原回执重新入 Inbox 并配对 | 不重新执行业务工具 |
| 等待审批时重启 | 从 approval 和 pending 调用恢复 | 不能依赖内存 Future |
| 租约过期后旧 Worker 返回 | fence/CAS 拒绝提交 | 仍须核对已发生副作用 |
| 用户撤权后恢复 | 刷新权限与清理受影响上下文 | 冻结版本不能冻结过期授权 |
| 取消时下游已接受操作 | 查结果、报告实际状态；必要时补偿 | 不声称取消成功等于回滚成功 |

---

## 6. Agent 定义、模型管理与发布

### 6.1 定义内容

Agent 定义至少包括名称、用途、系统规约、模型连接、输出契约、上下文策略、记忆作用域、Tool/Skill/MCP/知识库绑定、协作权限、沙箱策略、配额和失败策略。

发布时编译不可变 Manifest：agentVersion、Prompt 哈希、Skill 内容哈希、工具 schema 与实现版本、MCP 工具 schema 快照、知识索引版本、模型连接非密钥配置、runtimeKind、SDK/stateSchema 版本。凭据仅保存 secretRef，不固化明文或过期 Token。

发布态不可原地修改；回滚生成一个明确的版本选择。运行中不热替换 Prompt 或 Skill。紧急禁用、权限撤销和凭据轮换仍即时生效。

### 6.2 示例 Manifest

以下 YAML 是平台配置协议示例，不是 AgentScope 原生配置；数值是初始建议，需要测试后调整。

```yaml
apiVersion: scarlet.harness/v1
kind: AgentDefinition
metadata:
  key: ops-assistant
  name: 运维协作助手
spec:
  runtimeKind: AGENTSCOPE_HARNESS_V2
  modelRef: approved-primary-model
  promptRef: ops-system-prompt@3
  outputSchemaRef: diagnosis-result@1
  controlProfile: PLATFORM_MANAGED
  contextPolicy:
    strategy: TOKEN_BUDGET
    compact: true
    preserveToolCallPairs: true
    preservePendingQuestions: true
  memoryPolicy:
    mode: GOVERNED
    defaultScope: USER
    automaticWrites: CANDIDATE_ONLY
    enableDefaultFileMemory: false
  toolRefs: [metrics-query@1, logs-query@1, create-ticket@1]
  skillRefs: [incident-diagnosis@2]
  mcpToolRefs: [monitoring/query-events@1]
  knowledgeRefs: [ops-playbook@published]
  collaboration:
    handoff: true
    delegateWait: true
    delegateContinue: true
    maxDepth: 3
  executionPolicy:
    maxModelCallsPerSlice: 8
    maxToolCallsPerSlice: 12
    maxToolParallelism: 4
    writesRequireApproval: true
    unknownOutcomePolicy: RECONCILE
  sandboxPolicy:
    enabled: false
    arbitraryShell: false
```

发布时 `@published` 必须解析成具体版本，不允许运行时再次解析而漂移。

### 6.3 模型接入工作

保留模型目录与 secretRef，增加能力探测：流式输出、工具 schema、并行工具、多模态（启用时）、上下文窗口、结构化输出、取消与超时。用至少一个真实生产候选模型通过契约场景，不把本地 HTTP stub 验证当作真实模型质量验证。

模型回退只在能力、数据出域和授权均兼容时启用。不能在错误重试中无感切换到不支持 tool use 或更低安全等级的模型。输入/输出/缓存 token 与模型请求 ID 分别记录，缺失用量标为未知或估计。

---

## 7. 多轮会话与上下文工程

### 7.1 会话功能

会话列表、历史加载、新消息、补充材料、指定问题答复、重连、取消、清空上下文、新主题、归档与删除。消息携带 clientRequestId，重复提交返回同一 requestId；前端不直接上传一整份“权威聊天历史”覆盖后端。

普通续聊只传增量输入。正在运行时的新消息进入 Inbox，按序在安全点消费。不要把两个请求并发写入同一 AgentInstance。用户打断要区分“停止当前输出”和“取消业务目标”。

### 7.2 上下文组成与预算

```text
允许输入预算 = 模型窗口 - 最大输出预留 - 安全余量
系统规约 + 当前任务约束 + 工具 schema + 授权记忆
+ 检索证据 + 已提交摘要 + 最近消息 ≤ 允许输入预算
```

为工具 schema、检索材料和记忆分配独立上限，优先避免无关工具全量暴露。压缩前保留未解决问题、审批状态、工具调用/结果完整配对、引用来源和当前目标。压缩后执行结构完整性验证。

大结果转入 ArtifactStore，仅向模型返回摘要、大小、哈希和受控读取句柄，不返回可访问任意主机文件的路径。重新读取仍鉴权，并避免把大结果无限重新塞回上下文。

### 7.3 历史、摘要和审计

模型上下文可以压缩；审计事件和授权保留的原始消息独立持久化。但原始消息不是永久不可删除：按租户保留规则、用户删除和法定留存例外设计。全文、摘要和产物都标记权限来源依赖。

修改早期消息后的“重新生成”创建分支或新尝试，不能覆盖已经执行的工具回执。先前产生的外部副作用仍然存在，UI 必须提示并默认禁止重放写操作。

---

## 8. 长期记忆管理

### 8.1 四类数据分离

| 类型 | 示例 | 主存储与规则 |
|---|---|---|
| 会话记录 | 用户说过什么、工具返回什么 | 消息/事件库，按保留策略存储 |
| 工作上下文 | 当前目标、已执行步骤、未解决问题 | AgentState 与结构化任务状态 |
| 长期记忆 | 用户偏好、经确认的长期事实 | MemoryService，带作用域、来源和有效期 |
| 知识库 | 手册、制度、正式业务资料 | KnowledgeService，版本化与引用 |

AgentScope 内置记忆有每日文件、MEMORY.md 汇总及独立的压缩流程。[S11] 个人实验可以直接使用文件模式；本平台默认企业受控模式，MemoryService 为长期记忆权威，避免文件记忆与数据库记忆双写。

### 8.2 记忆项数据

`memoryId, tenantId, workspaceId, ownerType, ownerId, agentScope, type, content, sourceRefs, sourceVersion, sensitivity, status, validFrom, expiresAt, revision, supersedesId, createdBy, confirmedBy`。

置信度可以作为排序特征，但不能作为事实已验证的证明。来源必须标识为 USER_CONFIRMED、BUSINESS_TOOL、DOCUMENT 或 MODEL_INFERRED 等，不把模型推断包装为用户确认。

### 8.3 写入流程

```text
已提交的对话/事件
  → 持久化提取 Job
  → 脱敏和禁止记录规则
  → 提取候选事实及来源
  → 去重/冲突检测
  → CANDIDATE
  → 用户或管理员确认，或已批准的低风险自动策略
  → ACTIVE
  → 更新检索索引与版本
```

支持用户手工“记住这条”、编辑、撤销、删除、导出、查看来源。范围默认 USER；TASK 记忆随任务边界；WORKSPACE 共享记忆必须另行授权；不同 Agent 共享记忆必须绑定明确范围，而非共享一个全局文件。

### 8.4 与 Harness 默认记忆的关系

采用 GOVERNED 模式时关闭默认记忆自动写入与默认记忆工具，由平台提供 `memory_search / memory_read / memory_propose`。官方提供关闭 memory hooks/tools 的配置。[S11] 还必须检查普通 call 结束、压缩前提取、上下文溢出兜底三个路径，防止遗漏的自动写入；不能认为关闭一次 flush 就关闭所有维护行为。

保留可复用的会话压缩，但摘要不自动成为长期记忆。自动提取和整理使用持久化 Job，计入预算并支持重试，不用无法追踪的 fire-and-forget 任务。

### 8.5 删除与遗忘

删除先立即撤销召回资格，随后清理索引、缓存、派生摘要和记忆投影。维护 source_dependency 图或等价关联，定位来源撤权影响的上下文。对无法按来源准确拆分的压缩摘要，保守使整个摘要失效并由仍授权的消息重建。

已发送给模型的内容无法“撤回”。因此撤权时停止受影响的在途执行，下一次调用重新建上下文；必要时暂停整个会话。备份按保留周期到期清除；审计可以保留不含原文的删除回执，不能声称马上从所有备份物理消失。

---

## 9. Tool 管理、审批与操作账本

### 9.1 统一 ToolSpec

每个工具包含：名称、说明、输入/输出 JSON Schema、类型、实现版本、凭据引用、读取数据范围、是否有副作用、审批策略、超时、重试、幂等能力、操作状态查询、并发安全性、资源冲突键、审计策略。

类型先支持已注册 Java Bean、受控 HTTP、MCP、知识检索；脚本型在沙箱阶段开放。不要在管理页面允许任意 Java 类名反射、任意表达式或任意主机地址执行。

### 9.2 统一调用链

```text
参数 schema 验证
→ 身份与对象授权
→ 当前能力白名单 + 风险策略
→ 审批/参数摘要绑定
→ opId 幂等检查与预算预留
→ 实际连接器执行
→ 结果 schema 验证、脱敏、大小限制
→ 持久化结果与证据
→ 投递 SDK 回填事件
```

模型提供的 tenantId、userId、credential、审批状态等不得被接受为权威参数；这类字段由服务端注入。发布 manifest 冻结最大能力集合，实际执行权限取其与当前用户/工作空间策略的交集。

### 9.3 审批状态

`PENDING → APPROVED / DENIED / EXPIRED / CANCELLED`。

审批绑定 `operationId + toolVersion + canonicalArgsHash + targetResource + requester + approvalPolicyRevision`。参数、资源版本或审批策略发生实质变化时必须重新确认。批准只是同意执行当前操作，不自动授权未来所有同名工具调用。

审批提交后执行前仍检查用户、审批人权限和业务前置条件。防止两名审批人重复提交导致两次执行。修改参数要产生新操作意图/新审批，而不是修改旧回执。

### 9.4 重试分类

只读幂等工具可有限重试；写工具只有下游支持稳定幂等键且参数未变才自动重试。工具内部重试与平台重试统一次数预算，避免指数倍放大。连接失败、请求已发送但回执未知、业务拒绝和参数错误必须分型。

第一版写工具优先选“创建工单”一类有外部请求号、可查询状态的操作。不以重启服务器、删库等不可逆动作作为首个验证工具。

---

## 10. Skill 管理

### 10.1 格式与生命周期

Skill 是包含 SKILL.md 和可选 references/scripts/assets 的说明包，不是直接等同于工具函数。元数据与完整内容按需加载。[S12][S13]

生命周期：导入 → 解包检查 → 元数据/依赖验证 → 草稿测试 → 审核 → 发布只读版本 → 绑定 Agent → 废弃/回滚。

支持 ZIP/Git commit/受控目录来源；锁定内容哈希而不是浮动分支。导入校验包括路径穿越、绝对路径、符号链接、压缩炸弹、文件数量与体积、脚本依赖及许可证元数据。

### 10.2 权限与版本

Skill 引用工具需要在平台声明依赖；发布时验证实际可用工具。加载 Skill 即使激活 toolkit group，也不能突破平台白名单。工具目录与权限网关应覆盖动态激活路径。

运行时只读取已发布版本，不扫描用户可写目录来自动覆盖已批准 Skill。关闭 Agent 自动晋升/发布 Skill；允许模型提交改进草稿，但发布需审核和回归。[S14]

### 10.3 管理页面

包列表、版本差异、SKILL.md 编辑/预览、依赖工具检查、引用材料浏览、脚本风险标记、测试输入、执行轨迹与效果对比。第一版可以支持说明型 Skill；脚本型在沙箱验收之后启用，不以“Skill 已支持”隐含“任意脚本已安全支持”。

---

## 11. MCP 管理

### 11.1 管理对象

McpServer 定义传输类型、地址或受控启动配置、认证方式、secretRef、连接/请求超时、协议版本、允许工具、资源范围、健康状态和 schema 版本。服务配置、工具定义和运行时连接不是同一个对象。

AgentScope 客户端支持 STDIO、SSE 和 Streamable HTTP；框架提供发现和结果转换。[S15] 平台默认采用受控 Streamable HTTP；SSE 用于明确的旧端兼容，STDIO 只在隔离执行器中开放。

### 11.2 接入闭环

```text
管理员登记连接
→ 服务身份/网络地址校验
→ 握手与能力协商
→ 分页发现 tools（按需 resources/prompts）
→ schema 快照与变更比较
→ 平台审核工具风险和权限
→ 发布能力目录
→ Agent 绑定具体工具版本
```

工具变化不能静默扩大权限。服务器更新后 schema 不兼容则暂停相关工具，显示差异并重新测试。工具只读标记只是声明，不能替代可信的权限判断；不能让 readOnlyHint 自动绕过平台审批边界。[S15]

### 11.3 连接生命周期与安全

连接池以租户/工作空间/服务器/凭据主体/授权版本区分，禁止跨用户共享携带凭据的客户端。实现空闲回收、取消释放、重连退避、失效 Token 更新和关机清理；不要每次工具调用都启动新进程。

平台是 MCP Client。第一版公开支持的范围以 tools 为主；resources/prompts 经独立授权、内容大小限制后可接入。未明确实现的 sampling/elicitation/roots 等能力不宣告支持，也不默认允许服务端触发二次模型调用或读取宿主文件。

遵循 MCP 的令牌受众、最小范围和会话安全要求。校验配置 URL、重定向和 OAuth 元数据端点，控制 DNS 解析后的真实目标。企业内网 MCP 可以使用经过批准的内网白名单，但禁止任意内网探测和云元数据地址访问。不要把前端登录 Token 无校验地透传到 MCP/下游 API。[S16]

---

## 12. 知识库与可追溯检索

### 12.1 统一服务契约

`KnowledgeService.search(authorizedScope, query, indexVersion, limits)` 返回证据列表，而不只返回一段拼接文本。已有知识库直接实现适配器；没有时可建设本地 PostgreSQL + pgvector 检索实现。不要同时开发两套同功能知识引擎。

证据字段：`evidenceId, knowledgeBaseId, documentId, documentVersion, chunkId, title, pageOrSection, content, indexGeneration, retrievedAt, aclRevision`。只有定位到实际原文时才提供页码；不能由 LLM 生成来源位置。

### 12.2 本地托管知识库工作

上传文件 → 内容类型/大小/安全校验 → 持久化原始文档 → 文本解析与位置标注 → 切分 → 建立 embedding/关键词索引 → 校验 → 发布索引版本。

维护上传失败、解析失败、嵌入失败与索引发布失败的可重试 Job。更新文档生成新版本；删除文档立即撤销读取资格并清理索引；embedding 模型或维度变化新建索引代次并完成回归，不能混用不兼容向量。

继续支持当前 Markdown/TXT；PDF/DOCX 等格式逐项加解析测试，扫描件识别作为显式可选管线。人工审核解析质量差的资料，不把乱码切片直接当知识发布。

### 12.3 检索与回答流程

```text
身份与当前权限
→ 绑定的知识库/版本范围
→ 在授权集合中检索
→ 可选混合检索与重排
→ 去重、预算裁剪
→ 带 evidenceId 的上下文
→ 模型回答
→ 引用存在性/权限/版本核验
→ 内容支持程度检查
→ 返回回答和可定位原文
```

权限过滤必须尽量前置至检索服务与索引查询，不是把越权内容先发给重排模型再在页面隐藏。共享缓存以权限版本分区，答案缓存也必须遵守此规则。

“引用编号存在”和“引用确实支持结论”是不同验收项。前者可以确定性校验；后者需要规则、评测集和必要的人工复核，不承诺完全消除幻觉。无证据、证据不足或相互矛盾时明确表述，并请求补充资料。

### 12.4 知识撤权

撤权影响检索、缓存、产物、会话摘要、工作上下文及派生记忆。每轮调用和最终结果发布前检查 aclRevision；权限改变后使受影响上下文失效。已存在的用户对话记录按产品保留规则处理，不把“隐藏链接”当作完成撤权。

---

## 13. 工作空间、沙箱与产物

建议逻辑目录：

```text
/definitions/{agentVersion}/      只读 AGENTS.md / approved skills
/runs/{runId}/{instanceId}/       当前执行临时区
/artifacts/{artifactId}/          由 ArtifactService 管理的结果
```

路径只是一种投影视图；数据库保存 artifactId、归属、版本、哈希、媒体类型、大小、来源和有效期。下载使用短期授权与服务端鉴权，不能把主机绝对路径暴露给用户。

纯工具/RAG 助手默认不开通用 Shell。启用脚本后必须：非 root、只读基础镜像、按运行隔离工作区、CPU/内存/PID/时间/磁盘限额、限制出网、禁止挂载宿主根目录和 Docker socket、按能力注入短期凭据、清理残留进程及文件。文件读取和脚本执行都不能绕开平台数据权限。

镜像及依赖版本锁定、漏洞扫描、包来源审核和产物完整性检查进入发布流程。文件式 Skill 读取需经过目标 SDK 的工作空间抽象，不能在远程/沙箱模式混用宿主 Files API。[S7][S14]

---

## 14. 数据库设计与关键约束

表名示意，优先扩展既有表而不是平行复制 Task/Run。

| 分组 | 建议表/逻辑实体 | 核心字段或作用 |
|---|---|---|
| Agent/资源 | agent_definition, agent_version, resource_binding, model_connection | version、manifest、hash、secretRef |
| 会话 | conversation, message, request | owner、clientRequestId、seq、contentRef |
| 协作 | run, task, agent_instance, transfer, question | currentOwner、nodeId、status、generation |
| 执行 | execution_turn, agent_state_snapshot | stateVersion、fence、STAGED/COMMITTED、runtime/schemaVersion |
| 工具 | tool_definition, tool_version, tool_invocation, tool_attempt | schema、risk、opId、argsHash、externalRequestId |
| 审批 | approval | opId、argsHash、requester、approver、expiry、revision |
| Skill | skill_package, skill_version | manifest、contentHash、artifactRef、dependencies |
| MCP | mcp_server, mcp_tool_snapshot | transport、credentialScope、schemaHash、health |
| 知识 | knowledge_base, document_version, knowledge_chunk, index_generation | ACL、文档版本、embeddingProfile、定位信息 |
| 记忆 | memory_item, memory_revision, source_dependency | scope、status、source、supersedes、expiry |
| 可靠投递 | inbox, outbox, job | eventId、dedupeKey、lease、attempt、notBefore |
| 证据/产物 | evidence_ref, artifact | contentHash、source、accessScope、storageRef |
| 治理 | audit_event, usage_ledger, quota_reservation | principal、traceId、providerRequestId、budget |

通用字段为 tenantId、workspaceId、创建/更新时间、乐观版本，按对象需要增加归档/删除标识。对象所有权和跨表关联要验证同一租户；复合外键或服务端校验不能遗漏。

关键唯一约束：

```text
agent_version:          (tenant, workspace, agentKey, revision)
request:                (tenant, conversationId, clientRequestId)
agent_instance:         (tenant, runId, taskId, nodeId)
execution_turn:         (tenant, agentInstanceId, turnSeq)
tool_invocation:        (tenant, operationId)
SDK tool correlation:   (tenant, agentInstanceId, replyId, toolCallId)
inbox consumer:         (tenant, consumerId, eventId)
run_event:              (tenant, runId, seq)
```

原有单行 JSON 聚合阶段可先保持 Run/Task/owner 原子性，但 message、event、artifact、toolInvocation、checkpoint 应优先拆出，避免每个 token 或大日志触发重写整个 Run。多 Worker 开放前再完成热状态与作业拆分。

数据库原子事务覆盖“业务变更 + Outbox”；不包住网络调用。对象存储先上传并校验哈希再写元数据，孤立对象通过 GC 清理；不要假定对象存储与 PostgreSQL 具有共同事务。

---

## 15. REST、事件与页面设计

### 15.1 接口分组

沿用 `/api/v1`，既有 Agent/Workflow/Run 接口兼容演进，新增分组：

| 分组 | 关键接口与动作 |
|---|---|
| Agent | 创建/编辑草稿、校验、发布、版本比较、测试、回滚选择 |
| Conversation | 创建、列表、历史、POST messages、归档、删除 |
| Run | 创建、快照、SSE、取消、恢复、预算追加、输出与产物 |
| Tool | 登记、schema 校验、版本发布、受控测试、调用历史 |
| Approval | 待办、详情、批准、拒绝、失效、执行结果 |
| Skill | 上传/导入、文件浏览、版本、审核、发布、测试 |
| MCP | 连接配置、测试、discover、schema 差异、工具发布 |
| Memory | 查询、候选确认、手工创建、编辑、撤销、遗忘 |
| Knowledge | 文档上传、索引任务、发布、检索测试、引用定位、撤权 |
| Ops | 审计、用量、失败重试、UNKNOWN 核对、健康检查 |

异步接口返回 202 与 requestId/runId；校验失败 400/422，未授权 401，禁止 403，版本/幂等冲突 409，配额不足 429，不能把正在审批视为 HTTP 500。删除/修改需校验影响范围并使用 expectedRevision；列表分页。

消息入站示例：

```json
{
  "clientRequestId": "9bf662bf-49f5-468b-8905-493269453945",
  "content": [{"type": "text", "text": "请查询异常原因，并在需要时提出工单草稿。"}],
  "attachments": [],
  "mode": "CONTINUE"
}
```

审批提交只包含 approvalId、expectedRevision 和 decision；不能让前端提交一份被修改过的工具参数直接执行。

### 15.2 平台事件

统一事件信封：`eventId, runId, taskId, agentInstanceId, turnId, seq, type, occurredAt, payloadVersion, payload`。

主要事件：request.accepted、turn.started、text.delta、tool.proposed、tool.started、tool.completed、approval.required、question.required、task.handoff、task.delegated、context.compacted、memory.proposed、evidence.retrieved、artifact.created、turn.committed、run.completed、run.failed。

不要向前端泄露 SDK 内部序列化对象。UI 以工具、证据、计划摘要、审批与验证结果解释执行过程，不依赖模型隐藏思维链。

SSE 支持 after/Last-Event-ID，事件按 seq 去重；断线不重新提交消息。文本可合并成有界批次持久化，而不是每 token 一行 SQL。超出事件保留窗口时返回重新加载快照指示。未提交文本在异常后用明确的 aborted/reset 事件处理，不让用户把它当最终答案。

### 15.3 用户界面模块

| 页面 | 必须完成的交互 |
|---|---|
| Agent 管理 | 创建、复制、版本、能力绑定、发布前问题提示、试运行 |
| 会话工作台 | 多轮输入、上传、任务进度、问题卡、审批卡、引用、取消、续聊 |
| 工具中心 | 参数表单、风险、连接器、schema、测试与调用回执 |
| Skill 中心 | 包导入、内容预览、依赖、差异、审核发布 |
| MCP 中心 | 配置、连通性、工具发现、变更提示、启停 |
| 记忆中心 | 查看来源、候选确认、编辑、有效期、遗忘进度 |
| 知识中心 | 文档版本、入库任务、失败重试、检索效果、权限 |
| 审批中心 | 操作摘要、资源、请求人、参数差异、批准/拒绝、真实执行状态 |
| 运行调试 | Task 树、owner、实例、片段、检查点、工具回执、证据与用量 |
| 系统管理 | 身份、工作空间策略、凭据引用、预算、审计和维护任务 |

先复用现有 React 工作台，按页面拆分 App.tsx，不重新建设另一套后台。加载、空状态、错误、无权限、过期审批、断线重连和取消中的状态都必须有明确 UI。

---

## 16. 安全、成本和可观测性

### 16.1 身份与权限

接入现有身份系统，角色可以从管理员、Agent 编辑者、发布审核者、执行用户、审批人和审计人开始，再以对象 ACL 限定知识、工具、记忆、文件与工作空间。初期单工作空间也必须把 owner 与 scope 字段落全。

发布权限、执行权限与工具数据权限分开。任务委派不能扩大数据权限；服务账号替用户执行时必须记录委托链和有效范围。

### 16.2 提示注入与供应链

外部文档、工具结果、记忆和 Skill 均需明确可信级别。文档中的“忽略规则”“把密钥发送到某处”是数据，不改变权限。所有外部动作由网关确定性授权。防护不能只是一段系统提示词，也不能声称过滤器能完全识别注入。

密钥来自服务端密钥管理设施，日志与追踪默认不收集完整 Prompt、凭据、敏感工具输出。测试模式也不能绕过生产环境的写审批与网络限制。

### 16.3 配额与预算

预算维度包括模型 token、模型调用次数、工具调用次数、执行时长、并行数、子任务深度/数量、文件大小和外部服务调用配额。并行派发前预留子预算，结束后结算，避免父子任务分别只检查余额而超支。

主 Agent、子 Agent、摘要、记忆提取、embedding、rerank 都要记账。缓存 token 是输入 token 的细分还是另一个计费维度应按提供方语义记录，不能重复相加。费用依赖版本化价格表；没有可靠价格就显示 token 和“费用未知/估计”，不伪造精确金额。

### 16.4 观测

用 OpenTelemetry 关联 request → run → task → instance → turn → model/tool。AgentScope 提供相关追踪中间件，平台补充业务 ID、审批、恢复与成本字段。[S10]

指标包括排队时间、首字延迟、总时长、模型/工具错误率、上下文压缩次数、引用覆盖、知识空召回、审批等待、UNKNOWN 数量、恢复次数、过期租约、重复回执、每任务 token 和费用。操作审计与性能 trace 分开存储，不因 trace 采样而丢失写操作审计。

---

## 17. Project_scarlet 的迁移路线

### 17.1 现有文件到新职责

| 已确认文件 | 建议处理 |
|---|---|
| core/AgentRunner.java | 保留 V1 适配，新增有界片段 V2 契约 |
| core/Engine.java | 保留协作规则；只增加新的暂停/回填命令，不嵌 SDK |
| core/Domain.java | 增加运行类型、版本清单、审批、操作回执等引用 |
| app/Worker.java | 升级领取、片段提交、工具派发、取消与恢复协调 |
| app/JdbcStore.java | 从聚合库逐步拆出快照、事件、操作账本及 Job |
| app/Resources.java | 分离 ModelService、ToolRegistry、KnowledgeService |
| app/Catalog.java | 扩展 Agent 发布编译、能力绑定与版本校验 |
| app/Security.java | 替换本地管理员 Token 的生产认证实现；保留本地开发 profile |
| frontend/src/App.tsx | 按会话、Agent、资源、审批和运行调试拆页面 |
| scripts/restart_test.py | 扩展审批/工具回执/交接/UNKNOWN/租约恢复场景 |

文件存在性由固定提交树核对；以上是改造建议，不代表已审计每个文件内部实现。[S1][S3][S4]

### 17.2 兼容原则

旧 Run 使用旧 runtimeKind 和旧序列化格式完成；新 Run 按 feature flag 选择 AGENTSCOPE_HARNESS_V2。先灰度一个只读 Agent，再逐步迁移整个工作流。

不得将旧 JSON 动作历史原样当 AgentState 反序列化。迁移需要专门导入器，生成新的 SDK 上下文并记录来源；不能迁移的在途实例先完成或停止，不做隐式跨版本续跑。

数据库用增量迁移增加字段和表，已有列先保留；新旧 Runner 同时可读必要公共字段。代码回滚不等于快照格式可回滚：只将兼容状态交给旧代码，不兼容的 Run 由对应版本 Worker 完成。

---

## 18. 开发工作分解与里程碑

不按“先把页面全做完，再接运行时”的顺序。先验证状态恢复与副作用边界，再推进六项能力。

| 阶段 | 开发内容 | 交付件 | 通过门槛 |
|---|---|---|---|
| M0 技术定版 | SDK/BOM 锁定、Java17 编译、外部工具暂停回填、缓存/状态行为验证 | ADR、依赖清单、契约测试、兼容报告 | 两用户隔离、暂停重启恢复、不执行未提交工具意图 |
| M1 单 Agent 闭环 | AgentRunnerV2、Manifest、模型、会话、SSE、快照协调、受控只读工具 | 运行适配层、会话页、只读工具目录 | 真实模型多轮、断线重连、进程重启、结构化错误 |
| M2 六能力闭环 | GOVERNED 记忆、Skill、MCP、知识检索与引用、上下文预算 | 资源管理页、六能力场景、入库与记忆 Job | 所有能力可配置/发布/调用；无串会话、无越权召回 |
| M3 写审批与协作 | ToolGateway、操作账本、审批、handoff/delegate、结果回填 | 审批中心、协作适配、故障恢复测试 | 无审批写拒绝；审批重启；父继续；当前 owner 收结果 |
| M4 生产加固 | 多 Worker、fence、细粒度撤权、预算、审计、沙箱（启用脚本时） | 生产部署、告警、回滚/恢复手册 | 故障注入、安全门禁、备份恢复、容量回归 |
| M5 场景验收 | 真实模型/真实只读系统/受控写系统端到端 | 评测报告、验收证据、限制清单 | 产品流程与技术门槛共同通过 |

M1 的权限、幂等和数据归属是后续阶段的基础，不留到 M4 才补。M4 的“安全加固”是扩展，不是第一版无安全措施的理由。

### 18.1 可直接建 Issue 的工作包

| 编号 | 工作包 | 前置 | 负责角色 | 验收交付 |
|---|---|---|---|---|
| H00 | 锁定依赖、构建 Java17 CI | 无 | 后端/平台 | 制品可解析、无 Java21 API 依赖 |
| H01 | AgentScope 生命周期与外部工具契约验证 | H00 | 后端 | 暂停、恢复、异常、取消、事件配对报告 |
| H02 | 定义对象/状态/错误/事件协议 | H01 | 架构/后端 | DTO、Schema、状态迁移表 |
| H03 | 平台命名空间和身份传递 | H02 | 后端/安全 | 跨用户/租户隔离测试 |
| H04 | AgentRunnerV2 与 V1Adapter | H02 | 后端 | 旧领域测试保持通过 |
| H05 | ManifestCompiler 与发布校验 | H02 | 后端 | 发布不可变与版本漂移测试 |
| H06 | 模型连接及能力契约 | H00,H03 | 后端 | 真实模型工具/流式/取消验证 |
| H07 | StateStoreAdapter 暂存与提交 | H01,H04 | 后端 | STAGED 不被恢复、CAS/fence 测试 |
| H08 | TurnCoordinator 与输入游标 | H07 | 后端 | 原子提交与消费去重测试 |
| H09 | 会话 API、Request 幂等 | H03,H08 | 后端 | 重复消息只创建一项请求 |
| H10 | 平台事件映射与 SSE | H08 | 后端/前端 | 重连、去重、未提交文本清理 |
| H11 | 会话页面与运行状态 | H09,H10 | 前端 | 问题卡、取消、错误与空状态 |
| H12 | ToolSpec/注册目录/版本 | H05 | 后端 | Schema 与权限绑定校验 |
| H13 | ToolGateway 与结果封装 | H03,H12 | 后端 | 所有连接器通过授权网关 |
| H14 | ToolInvocation/Attempt 账本 | H08,H13 | 后端 | opId 稳定、回执复用 |
| H15 | Java/HTTP 连接器 | H13,H14 | 后端 | 超时分型、主机白名单、受控测试 |
| H16 | Approval 域与接口 | H14 | 后端 | 参数哈希绑定、过期、拒绝、幂等 |
| H17 | 审批页面 | H16,H10 | 前端 | 明确展示对象、参数与真实执行状态 |
| H18 | ContextAssembler 与压缩策略 | H06,H07 | 后端 | 工具消息配对、token 预算、摘要保真 |
| H19 | ArtifactService 与大结果卸载 | H03,H18 | 后端 | 有界内容、授权读取、清理 |
| H20 | MemoryService 与来源版本 | H03,H18 | 后端 | 四类范围、冲突、失效、手工记忆 |
| H21 | 记忆提取 Job/确认/遗忘 | H20,H08 | 后端 | 无隐藏双写、来源撤权后不召回 |
| H22 | 记忆管理页 | H20,H21 | 前端 | 候选确认、来源、遗忘进度 |
| H23 | Skill 包校验与仓库适配 | H05,H12,H19 | 后端 | 哈希锁定、路径安全、依赖校验 |
| H24 | Skill 中心与回归测试 | H23 | 前端/QA | 导入、差异、发布、绑定、试运行 |
| H25 | MCP 配置/客户端/凭据池 | H03,H13 | 后端 | 主体隔离、连接回收、协议协商 |
| H26 | MCP 发现、schema 审核与调用 | H25,H12 | 后端 | 变更不扩大权限、断连恢复 |
| H27 | MCP 管理页 | H26 | 前端 | 测试、发现、工具选择、状态提示 |
| H28 | KnowledgeService 与授权证据协议 | H03,H18 | 后端 | 受控检索与引用验证 |
| H29 | 文档版本/入库 Job/本地索引或外部适配 | H28,H19 | 后端 | 更新、删除、重建索引、失败恢复 |
| H30 | 知识管理与引用定位页 | H29 | 前端 | 文档状态、检索试验、原文定位 |
| H31 | 协作外部工具适配 | H04,H08,H14 | 后端 | handoff/wait/continue 与旧规则一致 |
| H32 | 当前 owner 投递与待决调用闭合 | H31 | 后端 | 转交后子结果不误唤醒旧实例 |
| H33 | Worker 租约/fence/多副本 | H08,H14 | 后端/平台 | 双 Worker 争抢、旧代次返回拒绝 |
| H34 | 全链路撤权与缓存失效 | H20,H28,H23,H25 | 后端/安全 | 记忆/摘要/产物/索引同时受控 |
| H35 | 预算预留与费用台账 | H06,H14,H31 | 后端 | 并行不超拨、所有模型子调用记账 |
| H36 | OpenTelemetry、指标与审计 | H10,H14 | 后端/平台 | ID 关联完整、敏感数据脱敏 |
| H37 | 隔离脚本/STDIO 执行器 | H19,H23,H25 | 平台/安全 | 无宿主越权、资源限额、清理验证 |
| H38 | 生产身份与对象权限 | H03 | 后端/安全 | RBAC/ACL、发布/审批职责隔离 |
| H39 | 故障注入与 UNKNOWN 核对页 | H14,H16,H33 | QA/后端/前端 | 超时成功/回执丢失可核对 |
| H40 | 备份恢复/升级/回滚 | H07,H29,H33 | 平台/QA | 恢复演练和快照兼容报告 |
| H41 | 真实场景验收与发布清单 | 全部启用能力 | 产品/QA/研发 | 证据包、已知限制、版本清单 |

角色不是额外组织要求，可以一人承担多个角色；发布验收和高风险审批应有独立复核。各工作包先固化契约再并行开发，避免多个开发者同时修改 Domain/Engine 却使用不同的状态语义。

---

## 19. 测试与验收

### 19.1 分层测试

单元测试覆盖状态迁移、scope、权限、参数规范化、审批哈希、幂等、token 预算。契约测试覆盖真实锁定 SDK 和模型/工具/MCP 接口。集成测试使用真实 PostgreSQL；H2 不替代事务和并发验证。端到端测试覆盖页面操作。质量评测用真实模型、固定输入集与带证据的参考结果。

测试可以使用确定性假模型做流程回归，但至少另有真实模型通过所有关键能力的验证。对模型输出的断言以结构、证据、业务结果为主，不要求逐字一致。

### 19.2 必测场景

| 编号 | 场景 | 通过条件 |
|---|---|---|
| T01 | 同一用户连续多轮 | 正确延续当前主题，不重复导入历史 |
| T02 | 用户 A/B 使用相同前端会话名 | SDK 状态、文件、记忆完全隔离 |
| T03 | 同一实例并发两条消息 | 串行处理，有确定顺序且不覆盖 |
| T04 | 一个请求重复提交 | 仅一个 Request/Turn 意图 |
| T05 | SSE 断连重连 | 不重跑模型、不重复消息 |
| T06 | 大历史触发压缩 | 待决问题、tool/result 配对保留 |
| T07 | 单个工具结果超限 | 有界摘要与授权产物引用 |
| T08 | 模型工具参数非法 | 返回结构化错误，无真实副作用 |
| T09 | 模型伪造身份/审批字段 | 网关忽略或拒绝，不能提权 |
| T10 | 无权限工具被 Skill 激活 | 即使 schema 可见也执行拒绝，修复可见性泄露 |
| T11 | Skill 路径穿越/符号链接 | 导入或读取时拒绝 |
| T12 | 运行中发布新版 Skill | 旧 Run 仍用原哈希 |
| T13 | MCP schema 更新新增危险工具 | 不自动暴露，需审核 |
| T14 | MCP readOnlyHint 虚假声明 | 不绕过平台风险/授权 |
| T15 | MCP 跨用户连接复用尝试 | 凭据池隔离 |
| T16 | MCP 断连/取消/Token 过期 | 正确释放、有限重试、不泄漏凭据 |
| T17 | MCP SSRF 与重定向 | 未批准目标被拒绝 |
| T18 | 记忆提取未经确认 | 保持候选，不当权威事实注入 |
| T19 | 记忆冲突/更正 | 明确替代关系，旧项不重复生效 |
| T20 | 删除记忆 | 检索/索引/投影不再返回，清理可追踪 |
| T21 | 未授权知识命中 | 检索及重排模型均不接收越权内容 |
| T22 | 知识引用不存在 | 输出验证拒绝或降级说明 |
| T23 | 引用存在但不支持结论 | 在质量评测中被识别和计分 |
| T24 | 知识撤权后续聊 | 清理派生摘要和旧上下文再调用 |
| T25 | embedding 模型/维度变化 | 新索引发布，不混用向量 |
| T26 | 写操作未审批 | 无下游请求 |
| T27 | 批准后参数改变 | 旧审批失效 |
| T28 | 重复批准 | 只执行一次操作意图 |
| T29 | 审批期间重启 | 原 approval/opId/待决调用保留 |
| T30 | 工具成功但回执丢失 | 查询同 opId 或 UNKNOWN，不盲重试 |
| T31 | 工具回执已存但 SDK 未消费 | 复用原回执，正确配对 |
| T32 | STAGED 快照后崩溃 | 不触发外部副作用 |
| T33 | Handoff 后交回 | 恢复原 instance 的上下文 |
| T34 | delegate_continue | 父任务确实在子结果返回前推进 |
| T35 | 子任务结束前父 Task 更换 owner | 结果到当前 owner，不误唤醒旧发起者 |
| T36 | 父任务提前完成/取消 | 依规定 join/detach/迟到结果处理 |
| T37 | 两 Worker 同时领取 | 仅一份有效提交，重复回执幂等 |
| T38 | 旧 Worker 丢租约后返回 | fence/CAS 拒绝写回 |
| T39 | 取消时工具已经成功 | 正确呈现成功与补偿需求，不虚假撤销 |
| T40 | 并行子任务争抢预算 | 预留原子，不超拨 |
| T41 | 摘要/记忆/嵌入模型调用 | 全部计入用量，可区分估计和实际 |
| T42 | 输出日志携带密钥 | 脱敏、不出现在 UI/trace/错误信息 |
| T43 | 沙箱尝试读取宿主/访问未批准网络 | 拒绝且审计 |
| T44 | 数据库/对象存储恢复 | 快照、产物、索引版本一致性检查 |
| T45 | SDK 升级后读取旧快照 | 兼容迁移或明确路由旧 Worker，不能静默损坏 |
| T46 | 旧 Runner 的在途任务 | 升级后仍按旧版本完成 |
| T47 | 模型返回空输出/格式错误 | 有限重试后结构化失败，不假成功 |
| T48 | 混合协作与写工具批次 | 全批记录且按策略拒绝，不先写后交接 |

### 19.3 容量与质量目标的设定方式

以下是验收方法，不是已测性能数字：在约定硬件、模型延迟和工作负载下逐级增加并发，记录 API 接收、排队、首字和总时长；比较同实例串行与跨实例并行；长时间运行后观察状态缓存、连接数、文件和线程是否增长。

确定性安全项要求测试集中零越权和零未审批写调用。业务完成率、引用支持率、记忆准确率设定带样本数的目标，由业务方批准；报告包含重复试验、失败分类和版本，不只报一个平均分。性能阈值必须在基线测量后固化为发布门禁。

---

## 20. 部署、运维与交付清单

### 20.1 环境

开发环境可单进程；集成环境必须用与生产相同的 PostgreSQL 语义；生产采用 API/Worker 独立容量、数据库备份、对象存储生命周期、密钥管理、TLS、出口策略和审计。

所有持久化时间使用 UTC；UI 按用户时区展示。配置文件和导出文档使用 UTF-8。请求超时、模型超时、工具超时、租约、审批有效期分别配置，不用同一个 timeout 混合表达。

### 20.2 关机与恢复

收到退出信号后停止领取新作业，给在途片段安全提交窗口；到期仍不能结束的记录为可恢复/待核对。无强制关闭下游动作成功的假设。启动后扫描过期租约、孤立 STAGED、未投递 Outbox、未核对 UNKNOWN 和索引任务；按不同策略恢复。

备份覆盖数据库、对象存储元数据关联、密钥引用配置和部署版本。定期演练恢复后继续审批和工具回填；仅成功导入 SQL 文件不算恢复验收。

### 20.3 必须交付

架构决策记录与边界说明；版本锁定和依赖清单；数据库迁移；AgentRunner/Tool/Memory/Knowledge SPI；OpenAPI 与事件协议；Agent/Tool/Skill/MCP 配置样例；真实模型及连接器配置手册；自动化测试与评测集；故障注入证据；部署/备份/恢复/回滚手册；安全与已知限制；一条从配置到执行再到证据输出的完整演示场景。

### 20.4 首条端到端验收业务流

管理员发布运维助手、绑定只读指标工具、日志 MCP、诊断 Skill、处置知识库和需审批的工单工具。用户提出问题；助手必要时补问；查询指标和日志；检索手册并引用；提出工单草稿；用户审批；工具以稳定 opId 创建工单；在回执写入附近注入崩溃；重启后通过外部请求号核对并继续；助手输出证据、工单编号和实际状态；用户确认偏好后形成候选/已确认长期记忆。最后验证另一用户无法读取本次记忆、资料或产物。

此场景串起六项核心能力、协作接口、人工审批和故障恢复。它通过后，再扩展到多 Agent 的父继续、转交和结果汇总场景。

---

## 21. 关键风险与决策门槛

| 风险 | 处置 | 必须作出的决策 |
|---|---|---|
| 文档与发布制品 API 不一致 | 锁版本，契约测试，必要时查该 tag 源码 | M0 不通过不进入全面开发 |
| SDK 快照与业务提交分离 | 暂存快照 + 原子指针提交 | 不采用边流边执行副作用 |
| 默认文件记忆与数据库并行写入 | GOVERNED 模式关掉默认写入与工具 | 只保留一个长期记忆权威 |
| SDK 动态 Skill/MCP 工具绕过策略 | 编译期过滤 + 执行期网关再校验 | 不能只配一份提示词白名单 |
| 外部操作结果未知 | opId、状态查询、UNKNOWN 人工核对 | 不提供任意写操作无条件重试 |
| 任务归属出现双调度 | 平台协作层唯一管理 Task | 关闭隐藏的业务子 Agent 调度路径 |
| 权限撤销后旧上下文残留 | 来源依赖、版本失效、重建摘要 | 撤权覆盖上下文，不只是检索接口 |
| 单体聚合写放大 | 分阶段拆事件/回执/产物/快照 | 多 Worker 前完成热状态验证 |
| 低质量效果被正常 HTTP 返回掩盖 | 真实模型/真实证据评测 | 结构验证与业务质量分别验收 |

**实施顺序总结：先拿下身份隔离、暂停回填和提交协议，再接六项能力；先完成单 Worker 的受控闭环，再开放多 Worker；用真实业务证据验证成功，不以“Agent 能聊天”作为项目完成标准。**

---

## 来源与核实范围

以下来源用于区分已有事实、SDK 能力和本方案的自有设计。访问日期为 2026-09-24；滚动文档可能变化。仓库引用固定在已读取提交，不推断其他分支已有同样代码。

[S1] Project_scarlet PR #1 元数据与固定提交文件树。`https://github.com/NatsumeWeiXia/Project_scarlet/pull/1`

[S2] Project_scarlet README，提交 e100f0fa。`https://github.com/NatsumeWeiXia/Project_scarlet/blob/e100f0fa0e46b625ff4222f0af0a428b9a8b4b2b/README.md`

[S3] Project_scarlet 架构说明，提交 e100f0fa。`https://github.com/NatsumeWeiXia/Project_scarlet/blob/e100f0fa0e46b625ff4222f0af0a428b9a8b4b2b/docs/ARCHITECTURE.md`

[S4] Project_scarlet AgentRunner.java，提交 e100f0fa。`https://github.com/NatsumeWeiXia/Project_scarlet/blob/e100f0fa0e46b625ff4222f0af0a428b9a8b4b2b/src/main/java/io/scarlet/core/AgentRunner.java`

[S5] AgentScope Java Quickstart。`https://java.agentscope.io/v2/en/docs/quickstart`

[S6] AgentScope Java Release Notes。`https://java.agentscope.io/v2/en/docs/others/release-notes`

[S7] AgentScope Java Harness Architecture。`https://java.agentscope.io/v2/en/docs/harness/architecture`

[S8] AgentScope Java Context & AgentState。`https://java.agentscope.io/v2/en/docs/building-blocks/context`

[S9] AgentScope Java Agent，外部执行与恢复。`https://java.agentscope.io/v2/en/docs/building-blocks/agent`

[S10] AgentScope Java Middleware。`https://java.agentscope.io/v2/en/docs/building-blocks/middleware`

[S11] AgentScope Java Memory。`https://java.agentscope.io/v2/en/docs/harness/memory`

[S12] AgentScope Java Skill。`https://java.agentscope.io/v2/en/docs/harness/skill`

[S13] Agent Skills Specification。`https://agentskills.io/specification`

[S14] AgentScope Java Going to Production。`https://java.agentscope.io/v2/en/docs/others/going-to-production`

[S15] AgentScope Java Tool（含 MCP、Skill 与 external execution）。`https://java.agentscope.io/v2/en/docs/building-blocks/tool`

[S16] Model Context Protocol Security Best Practices，2025-11-25 版本路径。`https://modelcontextprotocol.io/docs/2025-11-25/tutorials/security/security_best_practices`

[S17] Spring Boot 3.5 System Requirements。`https://docs.spring.io/spring-boot/3.5/system-requirements.html`

[S18] AgentScope Java Permission System。`https://java.agentscope.io/v2/en/docs/building-blocks/permission-system`

[S19] AgentScope Java Context Compaction。`https://java.agentscope.io/v2/en/docs/harness/compaction`
