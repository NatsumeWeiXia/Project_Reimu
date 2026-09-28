package io.reimu.core;
import io.reimu.core.Domain.Operation;
import java.util.*;
/** Platform-owned boundary. Implementations must preserve approval and identity checks. */
public interface McpService {
    record Reply(String content, boolean error, boolean unknown) {}
    boolean enabled();
    List<Map<String,Object>> catalog(String scope);
    List<Map<String,Object>> discover(String scope, String name);
    Reply execute(Operation operation);
    static McpService disabled() {
        return new McpService() {
            public boolean enabled(){return false;}
            public List<Map<String,Object>> catalog(String scope){return List.of();}
            public List<Map<String,Object>> discover(String scope,String name){throw new Fault(503,"MCP_DISABLED","当前部署未启用 MCP");}
            public Reply execute(Operation o){return new Reply("MCP_DISABLED",true,false);}
        };
    }
}
