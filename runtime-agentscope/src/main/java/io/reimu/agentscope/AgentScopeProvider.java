package io.reimu.agentscope;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reimu.core.*;
import java.util.Set;
/** Discovered only when the real AgentScope dependency module is present. */
public final class AgentScopeProvider implements RuntimeProvider {
    public AgentRunner createRunner(String modelId){return new AgentScopeRunner(new ObjectMapper(),Set.of(modelId));}
    public McpService createMcp(String config){return new McpGateway(config,new ObjectMapper());}
}
