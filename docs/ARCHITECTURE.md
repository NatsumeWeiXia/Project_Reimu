# 实现架构与关键约束

## 模块依赖

`core` 不依赖 SDK、Spring、React。`runtime-agentscope` 实现核心 SPI 并包含真实 AgentScope/MCP 依赖。`server` 只编译依赖 core，运行时包含 runtime 模块，通过 ServiceLoader 获取 provider；这一边界让领域与 HTTP 可单独验证，但不能用它代替 SDK 编译。

会话列表和聊天组件没有独立数据库。Java 状态为权威；浏览器 Projection 只接收公开事件。SDK 消息和状态快照不发送到浏览器。浏览器没有模型密钥、MCP 凭据或工具执行权限。

## 执行协议

`send → queued → claim(generation) → SDK/DemoRunner → stage outcome → transaction(checkpoint + tool intents + events) → dispatcher → receipts → next slice`。

副作用不在 SDK 工具事件回调里执行；每个工具请求分配平台 operationId 并检查发布白名单/参数。审批绑定 operationId、参数哈希、版本与到期时间。外部操作已经派发但结果未知时进入 unknown/needs_attention，不自动重发。取消不意味着回滚外部操作。

当前 Run 是单 Agent 输入任务，不存在 Task 树；一个 Conversation 保留一个 AgentInstance 上下文，多个输入顺序执行。不能把这个模型描述成已完成多 Agent 协作。

## 持久化

JDBC 全量聚合事务是小规模实现：`reimu_state` 保存 JSONState，`reimu_process_guard` 持有进程期间锁。快照版本、事件游标、操作账本和 UI 数据在同一提交中变化。H2 强制无延迟刷盘；PostgreSQL使用默认同步提交条件，部署方不能关闭 fsync 来换取虚假的可靠性。

不在数据库事务中等待模型或外部 MCP。CPU/IO 大结果有边界，聚合容量 32M 字符；目前没有自动归档和拆表迁移，接近上限必须停用/迁移，不能盲目扩大阈值。

## 前端公开协议

初始 snapshot 提供流 ID、水位与消息；SSE 从水位恢复。事件包含 previousCursor，前端不把游标转成 number，发生缺口/不同流/权限版本变化时重新读取快照。文本按 UTF-16 offset 和 attemptId 追加，完整消息再进行校对。旧请求和旧 controller 代次不写入新身份的页面。

消息引用 `(artifactId,revision)`；产物类型注册为 ReactFlow、Table、Markdown、Web。获取与下载必须服务端鉴权；打开不调用模型。图节点、表格行和 Markdown 块通过稳定 ID 选择；表格引用还校验查询快照与所选行。

## 接口清单

真实接口以 `server/.../Api.java` 和前端 `core/api.ts` 为准。REST 基础前缀 `/api/v1`；会话 SSE 为 `/conversations/{id}/stream?streamId=...&after=...`，同目录 `/events` 是 JSON 回放，不混淆二者。

主要类别：auth/me、ui-capabilities、agents/publish、conversations/snapshot/messages、requests/by-client-id、runs/cancellation-requests、approvals/decisions、questions/answers、resources/knowledge/search、memories/decision、mcp/discover、artifacts/revisions/table-queries/download。

## 禁止默认开启

无任意 Shell、脚本 Skill、STDIO MCP、任意网页代理、任意前端注入工具、用户传入 Java 类名、SQL 生成执行、跨用户共享 SDK Session。默认关闭 SDK 子 Agent、自动记忆、工作空间隐式上下文和自动压缩；后续必须在平台治理边界内逐项接入。
