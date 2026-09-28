package io.reimu.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.reimu.core.AgentRunner;
import io.reimu.core.Fault;
import io.reimu.core.ToolRegistry;
import reactor.core.publisher.Mono;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static io.reimu.core.Data.*;

/** AgentScope 2.x adapter. Full Maven/SDK integration must pass the supplied CI before enabling live. */
public final class AgentScopeRunner implements AgentRunner {
    private final ObjectMapper json;private final Set<String> models;
    public AgentScopeRunner(ObjectMapper json,Set<String> models){this.json=json;this.models=Set.copyOf(models);}
    static final class ExternalTool extends ToolBase {
        ExternalTool(ToolRegistry.Spec s){super(ToolBase.builder().name(s.name()).description(s.description()).inputSchema(s.inputSchema()).readOnly(false).concurrencySafe(false).externalTool(true));}
        @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> args,PermissionContextState ctx){return Mono.just(PermissionDecision.allow("Only suspend and propose; platform reauthorizes actual execution"));}
    }
    @Override public Outcome advance(Input input,Consumer<String> delta) throws Exception {
        Fault.require(models.contains(input.agent().model),403,"MODEL_DENIED","模型未在部署白名单中");
        StagedSdkStore store=new StagedSdkStore(input.identity().scope(),input.instanceId(),input.checkpoint(),json);
        Toolkit toolkit=new Toolkit();for(String name:input.agent().tools)toolkit.registerTool(new ExternalTool(ToolRegistry.spec(name)));
        ToolsConfig config=new ToolsConfig();config.setAllow(input.agent().tools.isEmpty()?List.of("__no_tools__"):input.agent().tools);config.setMcpServers(Map.of());
        Path temporary=Files.createTempDirectory("reimu-harness-");
        try(HarnessAgent agent=HarnessAgent.builder().name("reimu-"+input.agent().id).sysPrompt(input.agent().systemPrompt).model(input.agent().model)
                .toolkit(toolkit).toolsConfig(config).workspace(temporary).stateStore(store).maxIters(2).maxRetries(0)
                .skillsEnabled(false).disableSubagents().disableDynamicSubagents().disableMemoryTools().disableMemoryHooks()
                .disableWorkspaceContext().disableAtPathExpansion().disableCompaction().disableToolResultEviction().enableAgentTracingLog(false).build()){
            // Built-in shell/filesystem or dynamically installed tools must not create a bypass.
            Fault.require(agent.getToolkit().getToolNames().equals(new HashSet<>(input.agent().tools)),500,"SDK_TOOL_SURFACE","SDK 工具清单不符合发布清单，拒绝执行");
            List<Msg> messages=new ArrayList<>();
            if(input.replies().isEmpty())messages.add(new UserMessage(input.text()));
            else {List<ToolResultBlock> results=new ArrayList<>();for(var r:input.replies())results.add(ToolResultBlock.builder().id(r.id()).name(r.name()).output(List.of(TextBlock.builder().text(r.text()).build())).state(r.error()?ToolResultState.ERROR:ToolResultState.SUCCESS).build());messages.add(new ToolResultMessage(results));}
            var ctx=RuntimeContext.builder().userId(input.identity().scope()).sessionId(input.instanceId()).build();
            StringBuilder text=new StringBuilder(),batch=new StringBuilder();long[] flushed={System.nanoTime()};List<ToolCall> calls=new ArrayList<>();AtomicBoolean ended=new AtomicBoolean();
            agent.streamEvents(messages,ctx).doOnNext(event->{
                if(event instanceof TextBlockDeltaEvent t){text.append(t.getDelta());batch.append(t.getDelta());if(batch.length()>512||System.nanoTime()-flushed[0]>50_000_000L){delta.accept(batch.toString());batch.setLength(0);flushed[0]=System.nanoTime();}}
                else if(event instanceof RequireExternalExecutionEvent ext){for(var call:ext.getToolCalls())calls.add(new ToolCall(call.getId(),call.getName(),call.getInput()));}
                else if(event instanceof RequireUserConfirmEvent)throw new Fault(500,"SDK_PERMISSION_BOUNDARY","SDK 出现非预期审批边界，未执行操作");
                else if(event instanceof ExceedMaxItersEvent)throw new Fault(409,"SDK_STEP_LIMIT","执行片段达到限制，未声明业务成功");
                else if(event instanceof RequestStopEvent)throw new Fault(409,"SDK_STOPPED","SDK 已停止，未声明业务成功");
                else if(event instanceof AgentEndEvent)ended.set(true);
                // Thinking, raw tool arguments, credentials and debug traces are deliberately not projected.
            }).blockLast(Duration.ofSeconds(150));
            if(!batch.isEmpty())delta.accept(batch.toString());
            Fault.require(ended.get()||!calls.isEmpty(),502,"SDK_INCOMPLETE","未收到完整执行边界");
            return new Outcome(store.checkpoint(),text.toString(),calls,calls.isEmpty());
        }finally{try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
