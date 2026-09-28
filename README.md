# Project Reimu

Java 17 目标平台上的通用 Agent Harness 实现稿与 React 三栏会话工作台。

> **交付状态：本地实现稿，不是已经完成全部方案、也不是生产验收通过版本。**
> GitHub 仓库读取成功，但本次 `GitHub.create_file` 返回 `403 Resource not accessible by integration`，因此未创建远端文件、分支、提交或 PR。
> 已运行核心、真实 Spring Boot/H2 HTTP 与前端协议测试；**AgentScope 模块的完整 Maven 编译、React 依赖类型检查/构建、浏览器测试尚未通过验证**。详见 [验证报告](docs/VALIDATION.md) 与 [实现范围](docs/IMPLEMENTATION_STATUS.md)。

## 1. 结构

```text
core/                 平台领域、会话、事件、操作账本、产物、记忆与知识
runtime-agentscope/   真正依赖 AgentScope 的 Harness、外部工具、快照、MCP 适配
server/               Spring Boot REST/SSE、登录、JDBC、单 Worker
frontend/             React/assistant-ui 三栏工作台与四种渲染器
scripts/              构建、核心测试、HTTP/强杀恢复测试
.github/workflows/    Java 17 + 全依赖 + HTTP + 浏览器验证流程（尚未运行）
docs/                 原始方案、实现范围、验证结果和后续门禁
evidence/             本次实际执行的日志及依赖来源记录
```

平台只通过 `AgentRunner` / `RuntimeProvider` / `McpService` 连接 SDK。`demo` 使用明确标识的确定性测试执行器；`live` 必须加载真实 AgentScope 模块，**缺失或失败不会静默降级成 demo**。

本地验证中使用的 Spring/H2 JAR 来自本用户已授权读取的 Scarlet 构建产物，只提取依赖用于测试，不运行旧工程业务代码，不把旧 CI 结果当成本工程结果，不随源码包分发这些 JAR。

## 2. 本次已有能力

后端有会话增量消息、请求去重、发布版本、按身份隔离的消息/产物/记忆/知识、SSE 水位恢复、操作意图与 SDK 快照同事务提交、审批、补问、取消、工具结果未知不自动重试。记忆先进入候选，确认后使用；知识是**真实上传文本的关键词检索**，不是向量数据库。

工具目录是九个经过程序注册的能力，不是任意上传代码的平台：`knowledge_search`、`memory_search`、`memory_propose`、`skill_read`、`artifact_create`、`artifact_revise`、`user_ask`、`ticket_create`、`mcp_call`。其中 ticket 是 Reimu 内部测试工单，不连接生产工单系统。

前端源码含会话导航、搜索、置顶/归档/删除、用户信息、聊天、审批与补问卡、可调整三栏、固定版本产物标签、React Flow、服务器分页表格、Markdown、网址安全外链、选中内容引用及资源管理对话框。**尚未实际渲染验证这些 React 页面，不能把源码存在当成 UI 已验收。**

网址首版只提供外链降级，不宣称任意网站能在 iframe 内打开。图的拖动只改变本次查看布局；业务流程发布和执行不是拖动画布的隐含副作用。

## 3. 构建前提

需要 JDK 17、Maven 3.9、Node.js 22.16 或兼容版本、可访问 Maven Central/npm 的网络。建议首次构建在 CI 或隔离开发环境完成。

当前直接依赖声明已固定版本，但本次网络限制导致**尚无真实生成的 npm lockfile**；禁止编造 lockfile。首次 `npm install` 后审查、提交 `frontend/package-lock.json`，之后使用 `npm ci`。CI 会保留生成的 lockfile 作为证据。

```bash
bash scripts/build.sh
```

脚本会安装前端依赖、执行协议测试、进行完整 TypeScript 检查和 Vite 构建、把 dist 放进 Spring Boot 静态目录，然后执行整个 Maven reactor 的 `verify`。任何失败都会停止；没有跳过测试或假 SDK 替代品。

## 4. 本地显式演示

以下访问密钥由你本机生成，不需要也不应该发到聊天中。先构建，再执行：

```bash
export REIMU_MODE=demo
export REIMU_USERS_JSON="$(python3 -c 'import json,secrets; print(json.dumps({"admin":{"token":secrets.token_urlsafe(32),"admin":True}}))')"
# 仅本地 HTTP 体验使用 false；对外 HTTPS 必须 true。
export REIMU_SECURE_COOKIE=false
export REIMU_ORIGIN=http://localhost:8080
java -jar server/target/reimu-server-0.1.0-SNAPSHOT.jar
```

本地浏览器访问 `http://localhost:8080`。登录用户为 admin，密钥从你本地生成的 `REIMU_USERS_JSON` 中读取。`.env.example` 是变量说明，应用**不会自动解析 `.env` 文件**。

演示语句：

```text
生成演示流程、表格和报告
请创建工单
请补问我一个问题
记住以后用中文回答
上一条请求是什么？
```

演示模式只验证平台流程，不能用于评估实际 LLM 推理质量。切换到真实模型前必须完成第 7 节的门禁。

开发前端时单独运行 `npm --prefix frontend run dev`，后端仍在 8080，并将 `REIMU_ORIGIN` 精确设为实际 Vite 地址（例如 `http://127.0.0.1:5173`）；Vite `/api` 代理不会成为第二套后端。

## 5. 数据与部署限制

默认 H2 文件数据库位于 `.data`。应用对 H2 设置 `WRITE_DELAY 0`，避免默认延迟刷盘窗口内进程被强杀时丢失刚提交状态；这不是硬件断电、文件系统故障或磁盘故障保证。

生产候选配置可用 PostgreSQL：

```bash
export DB_URL=jdbc:postgresql://127.0.0.1:5432/reimu
export DB_USER=reimu
export DB_PASSWORD='<由部署环境注入>'
```

**PostgreSQL 路径本次未实测。** 当前 JDBC 存储是单行 JSON 聚合，最大约 32M 字符；数据库行锁强制单 Worker，不支持水平扩容。不能通过多开副本规避限制。扩大规模前必须把消息、事件、操作、作业与产物拆表并迁移。

存储中包含敏感业务数据，需要数据库权限、备份、磁盘保护和留存策略。禁止上传 `.data`、会话数据、访问密钥或模型凭据到 Git。

对外部署还需要 TLS、网关请求体限制（建议不超过 2MiB）、速率限制、出网规则、容量测试、企业身份认证和安全审计；当前命名访问密钥登录不是企业 SSO/RBAC。

## 6. 测试

```bash
# 无外部依赖的领域回归；需要 javac/java。
bash scripts/verify-core.sh

# 需要 TypeScript 已安装；不包含 React 渲染测试。
npm --prefix frontend run test:unit

# 完整编译和 JUnit；需要所有真实 Maven 依赖。
mvn -B verify

# 需要已构建可执行 JAR 与 Chromium。
cd frontend
npx playwright install chromium
npm run test:e2e
```

`verify-http.py` 是真实 HTTP 测试，可通过 `REIMU_TEST_SERVER_COMMAND` 启停服务器并执行 SIGKILL。脚本必须指向**可删除的测试数据库**，会创建用户会话、资料与内部工单。CI 中已配置测试身份和显式 demo 环境。

`verify-framework.sh` 可利用真实 Spring/H2 依赖在受限环境独立编译 core/server，**不编译 runtime-agentscope，也不构建前端**。它不是完整构建的替代验收。

## 7. 切换真实 AgentScope 前的门禁

先使 `mvn verify`、前端完整构建和真实浏览器测试全部通过；随后配置真实模型提供商凭据及 `REIMU_MODEL_ID`，将 `REIMU_MODE` 设为 `live`。模型 ID 使用锁定 SDK 的 ModelRegistry 命名方式，模型凭据只能存在服务端环境中。当前接口适配按官方 `v2.0.1` 源码编写，发布制品兼容性尚未由本次环境证实。

必须再验证真实模型的一轮回复、跨轮恢复、外部工具暂停/结果回填、取消、超时、工具白名单，以及工具意图提交前强杀不能产生外部副作用。仅通过 SDK 编译不能替代这些测试。

MCP 首版是部署配置固定的 HTTPS Streamable HTTP 适配，不支持任意 URL、STDIO、OAuth 流程或共享用户连接池。所有 MCP 调用都需审批，还必须通过配置中的工具 Schema 哈希审核。配置结构见 `McpGateway.Endpoint`；真实 MCP 尚未连通验证。出网 ACL、DNS/重定向治理和完整 JSON Schema 运行时校验是生产门禁，不能只依赖管理员填入了 URL。

## 8. 原方案尚未完整实现

多 Agent Task/交接/委派/并行协作、自动压缩与摘要、自动长期记忆提取/冲突治理、完整知识引用支持性校验、向量检索、附件/PDF 入库、任意脚本 Skill、沙箱、完整工具资产管理、撤权依赖传播、多 Worker、费用/配额/OpenTelemetry 等尚未交付。前端高级编辑/分支/版本比较、iframe 沙箱、上传附件、无障碍完整审计和容量验收也未完成。

界面能力接口对未完成项返回 false，禁止把按钮或类型定义视作功能已实现。详细差距和下一轮顺序见 `docs/IMPLEMENTATION_STATUS.md`。
