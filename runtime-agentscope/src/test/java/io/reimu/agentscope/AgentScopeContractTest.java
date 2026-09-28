package io.reimu.agentscope;
import io.reimu.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ServiceLoader;
import static org.junit.jupiter.api.Assertions.*;
/** Requires the real SDK dependencies. Written but not executed in the offline delivery session. */
class AgentScopeContractTest {
 @Test void serviceProviderExists(){assertTrue(ServiceLoader.load(RuntimeProvider.class).findFirst().orElseThrow() instanceof AgentScopeProvider);}
 @Test void allPlatformToolsSuspendRatherThanExecute(){for(var spec:ToolRegistry.all()){var tool=new AgentScopeRunner.ExternalTool(spec);assertTrue(tool.isExternalTool());assertEquals(spec.name(),tool.getName());assertEquals(spec.inputSchema(),tool.getParameters());}}
 @Test void noMcpConfigurationMeansNoClient(){assertFalse(new McpGateway("",new ObjectMapper()).enabled());}
}
