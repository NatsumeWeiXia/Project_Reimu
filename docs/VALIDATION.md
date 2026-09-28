# 验证报告

日期：2026-09-24。以下结果来自本次实现的实际命令，不引用 Scarlet 旧测试数量来充当 Reimu 的结果。

## 已执行结果

| 验证层 | 实际结果 | 环境与含义 |
|---|---|---|
| Java 平台核心 | 41 项通过 | javac `--release 17`；实际运行 JVM 21.0.11，不是已在 JDK 17 运行 |
| Spring Boot/H2 HTTP | 36 项通过 | 真实 Spring Boot 3.5.5、H2 2.3.232、Tomcat；确定性 DemoRunner |
| 前端协议和控制器 | 38 项通过 | Node 22.16.0 / TypeScript 5.8.3；不依赖 React 的真实源码测试 |
| 前端 TS/TSX 语法转译 | 12 个 src 文件通过 | 仅语法/单文件转译，不是依赖类型检查或 Vite 构建 |
| 整体 AgentScope Maven 构建 | 未执行完成 | 没有 Maven 可执行文件，且依赖网络不可用 |
| 完整 React 构建/浏览器测试 | 未执行完成 | npm 依赖无法下载；写有 5 条 Playwright 流程，未运行 |
| 真实模型 / MCP / PostgreSQL | 未验证 | 缺完整 SDK 构建、服务及必要凭据 |
| GitHub 提交/PR/CI | 未完成 | `create_file` 得到 403；没有任何远端写入成功 |

各层存在相同业务场景的不同验证，不把数量相加宣称互不重叠的独立业务场景。

## 核心覆盖

事务回滚、防御性拷贝、单 Worker、身份隔离、请求体哈希去重、回执、多轮恢复、事件水位、不可变旧事件、中文/Emoji UTF-16、工具意图先提交、发布版本、四种产物、审批参数/版本/过期/拒绝/重放、补问、取消、旧执行代次、未知副作用不重试、知识 ACL、Skill 元信息、记忆候选/遗忘、固定产物版本、非法 URL/图端点、实际引用选择、分页大整数及 CSV 公式转义。

## 真实 HTTP 测试

`verify-http.py` 启动本次编译的新服务，通过 Cookie 登录、Origin/CSRF、REST 与 SSE 操作真实数据库。调用的是明确的 DemoRunner，不是远程模型。测试在一个审批刚进入 pending 后对 JVM 发送 **SIGKILL**，重新启动服务、重新登录，再验证原审批 ID、会话消息与产物保留，随后审批并执行内部工单。

Spring/H2 依赖来自用户已授权下载的 `scarlet-java17-evidence`，构建产物 ID `10757173989`。只从其 JAR 提取真实第三方依赖，不复用 Scarlet 应用类、不执行 Scarlet 业务。当前 core/server 重新编译和运行；AgentScope 模块未在该离线类路径中，live 模式会因没有 provider 而失败，不会假装调用模型。

## 测试发现并修复的问题

- 事件数据原先引用可变消息对象，后续生成可能改写旧事件；改为事件写入时深复制，添加回归。
- H2 默认延迟刷盘时，刚提交的审批在立即 SIGKILL 后可能丢失；对 H2 初始化 `SET WRITE_DELAY 0`，未靠等待/删除强杀用例掩盖问题，重跑恢复测试通过。该配置不证明硬件断电持久性。
- SSE 客户端断开后全局错误处理试图往 event-stream 写 JSON；对 `AsyncRequestNotUsableException` 单独终止，不输出错误正文。
- React StrictMode 的 setup-cleanup-setup 会使自有 controller 永久关闭；改为生命周期代次和独立 AbortSignal，添加双启动回归，没有通过关闭 StrictMode 回避。
- SSE 多行数据累计大小、解析失败后的 reader 清理、旧身份网络回包、表格异步翻页覆盖与旧会话搜索回包已加隔离或限制。
- 引用版本浮点截断，以及选中表格行与查询快照不一致，已改为服务端拒绝并增加测试。

## 证据文件

`evidence/core-test.log`、`evidence/http-test.log`、`evidence/frontend-protocol-test.log`、`evidence/frontend-syntax.log`、`evidence/framework-provenance.json`、`evidence/environment.json`、`evidence/remote-write-status.json`。

服务日志是本地测试身份与临时数据库的信息，不包含生产凭据。各日志不能证明未执行的 SDK、浏览器、PostgreSQL或生产负载验证。

## 发布门禁

尚不能将本交付标为“前后端全部完成、生产可用”。至少需要真实 JDK17 的全依赖构建、依赖漏洞/许可证审查、实际 React 页面测试、真实 SDK 协议测试、模型/MCP 联调，以及原方案中尚缺的协作/治理功能。

H2 行为依据：官方 Commands 文档 `SET WRITE_DELAY` 与 Advanced 的 durability 说明（2026-09-24 查询）。地址：
https://h2database.github.io/html/commands.html#set_write_delay
https://h2database.github.io/html/advanced.html#durability_problems
