# 外部实现参考

查询日期 2026-09-24。以下用于编写适配代码，不等于 SDK 制品已成功构建或测试。

- AgentScope Java 官方源码 `v2.0.1`：HarnessAgent、ToolsConfig、ToolBase、AgentStateStore、ToolResultMessage、RequireExternalExecutionEvent、McpClientWrapper。https://github.com/agentscope-ai/agentscope-java/tree/v2.0.1
- AgentScope 工具与外部执行协议：https://java.agentscope.io/v2/en/docs/building-blocks/tool
- assistant-ui 自定义状态桥接：https://www.assistant-ui.com/docs/runtimes/custom/external-store
- assistant-ui Composer：https://www.assistant-ui.com/docs/primitives/composer
- H2 WRITE_DELAY：https://h2database.github.io/html/commands.html#set_write_delay

React/AgentScope 是真实依赖，不把示例假实现放入它们的包名空间。第三方 JAR/node_modules/字体没有包含在交付源码里。代码项目的对外许可证由仓库所有者决定；第三方依赖使用时遵守各自许可证，并在完整依赖解析后完成清单审查。
