package io.reimu.core;
/** ServiceLoader extension supplied by the actual AgentScope runtime module, never a fake SDK. */
public interface RuntimeProvider {
    AgentRunner createRunner(String modelId);
    McpService createMcp(String deploymentConfig);
}
