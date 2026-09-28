package io.reimu.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.reimu.core.Domain.Operation;
import io.reimu.core.Fault;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static io.reimu.core.Data.*;

/** Deployment-pinned Streamable HTTP only. No arbitrary URL, STDIO, OAuth proxy or shared client pool. */
public final class McpGateway implements io.reimu.core.McpService {
    public record Endpoint(String name,String url,String principalScope,String tokenEnv,List<String> tools,Map<String,String> schemaHashes){}
    private final List<Endpoint> endpoints;private final ObjectMapper json;
    public McpGateway(String config,ObjectMapper json){this.json=json;try{endpoints=config.isBlank()?List.of():json.readValue(config,new TypeReference<List<Endpoint>>(){});}catch(Exception e){throw new IllegalArgumentException("REIMU_MCP_JSON invalid",e);}for(var e:endpoints){URI u=URI.create(e.url());if(!u.getScheme().equals("https")||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||u.getQuery()!=null||e.principalScope()==null||e.tools()==null||e.schemaHashes()==null)throw new IllegalArgumentException("MCP requires a pinned HTTPS endpoint, explicit principal scope, tool allowlist and schema hashes");}}
    public boolean enabled(){return !endpoints.isEmpty();}
    public List<Map<String,Object>> catalog(String scope){return endpoints.stream().filter(e->e.principalScope().equals(scope)).map(e->map("name",e.name(),"tools",e.tools(),"transport","streamable_http","approval","always")).toList();}
    private McpClientWrapper client(Endpoint e){var b=McpClientBuilder.streamableHttp().name(e.name()).url(e.url());if(e.tokenEnv()!=null&&!e.tokenEnv().isBlank()){String token=System.getenv(e.tokenEnv());if(token==null||token.isBlank())throw new Fault(503,"MCP_CREDENTIAL","缺少 MCP 服务凭据");b.header("Authorization","Bearer "+token);}return b.build();}
    public List<Map<String,Object>> discover(String scope,String name){Endpoint e=endpoint(scope,name);try(McpClientWrapper c=client(e)){c.initialize().block(Duration.ofSeconds(15));return Objects.requireNonNull(c.listTools().block(Duration.ofSeconds(15))).stream().filter(t->e.tools().contains(t.name())).map(t->{Map<String,Object> schema=json.convertValue(t.inputSchema(),new TypeReference<Map<String,Object>>(){});return map("name",t.name(),"description",t.description(),"schema",schema,"schemaHash",hash(schema));}).toList();}}
    private Endpoint endpoint(String scope,String name){return endpoints.stream().filter(e->e.name().equals(name)&&e.principalScope().equals(scope)).findFirst().orElseThrow(()->new Fault(403,"MCP_DENIED","MCP 未授权给当前执行身份"));}
    public Reply execute(Operation o){boolean dispatched=false;
        try{Fault.require(o.decision.equals("approved"),403,"APPROVAL_REQUIRED","MCP 需要当前操作审批");Endpoint e=endpoint(o.scope,(String)o.args.get("server"));String name=(String)o.args.get("tool");Fault.require(e.tools().contains(name),403,"MCP_TOOL_DENIED","MCP 工具未审核");
            try(McpClientWrapper c=client(e)){c.initialize().block(Duration.ofSeconds(15));var tool=Objects.requireNonNull(c.listTools().block(Duration.ofSeconds(15))).stream().filter(t->t.name().equals(name)).findFirst().orElseThrow(()->new Fault(409,"MCP_TOOL_CHANGED","工具已移除"));
                String schemaHash=hash(json.convertValue(tool.inputSchema(),new TypeReference<Map<String,Object>>(){}));Fault.require(schemaHash.equals(e.schemaHashes().get(name)),409,"MCP_SCHEMA_CHANGED","MCP Schema 未审核或已变化，请管理员复核");
                dispatched=true;var result=c.callTool(name,object(o.args.get("arguments"))).block(Duration.ofSeconds(60));if(result==null)return new Reply("未收到工具回执",true,true);
                String content=json.writeValueAsString(result);if(content.length()>200000)return new Reply("工具返回超过限制，需人工核验实际结果",true,true);
                return new Reply(content,Boolean.TRUE.equals(result.isError()),false);
            }
        }catch(Exception e){return new Reply(e instanceof Fault f?f.code+": "+f.getMessage():"MCP 调用失败；未向浏览器输出内部异常",true,dispatched);}
    }
}
