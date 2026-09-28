package io.reimu.server;

import io.reimu.core.*;
import io.reimu.core.Domain.*;
import jakarta.servlet.http.*;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.reimu.core.Data.*;
import static io.reimu.core.Fault.*;

@RestController
@RequestMapping("/api")
public final class Api {
    private final Workspace w;private final Execution x;private final TableQueries tables;private final Auth auth;private final Environment env;private final McpService mcp;
    private final ScheduledExecutorService timers=Executors.newScheduledThreadPool(2);private final Semaphore streams=new Semaphore(32);
    public Api(Workspace w,Execution x,TableQueries tables,Auth auth,Environment env,McpService mcp){this.w=w;this.x=x;this.tables=tables;this.auth=auth;this.env=env;this.mcp=mcp;}
    record Login(String username,String token){}record NewConversation(String clientRequestId,String agentRef,String title){}
    record EditConversation(int expectedVersion,String title,Boolean pinned,Boolean archived){}record Send(String clientRequestId,String text,List<Map<String,Object>> contextReferences){}
    record Command(String clientRequestId){}record Publish(int expectedVersion){}record Approval(int approvalRevision,String operationId,String argsHash,String decision,String clientRequestId){}
    record Answer(int questionRevision,String text,String clientRequestId){}record MemoryDecision(int expectedVersion,String decision){}
    record ResourceInput(String kind,String name,String content,List<String> readers){}record Search(List<String> refs,String query){}
    record TableQueryInput(String sortColumn,boolean descending,String filter,int pageSize){}
    @GetMapping("/health") public Map<String,Object> health(){return map("status","UP","application","Reimu","mode",env.getProperty("reimu.mode","live"));}
    @PostMapping("/v1/auth/login") public Object login(HttpServletRequest r,@RequestBody Login body){auth.login(r,text(body.username(),64,"username"),text(body.token(),256,"token"));return me(r);}
    @PostMapping("/v1/auth/logout") public Object logout(HttpServletRequest r){r.getSession().invalidate();return map("status","logged_out");}
    @GetMapping("/v1/me") public Object me(HttpServletRequest r){Identity who=(Identity)r.getSession().getAttribute("identity");return map("principalId",who.principalId(),"tenantId",who.tenantId(),"workspaceId",who.workspaceId(),"admin",who.admin(),"csrfToken",r.getSession().getAttribute("csrf"),"permissionVersion","1");}
    @GetMapping("/v1/ui-capabilities") public Object capabilities(){return map("protocolVersion",1,"mode",env.getProperty("reimu.mode","live"),"artifactKinds",List.of("reactflow","table","markdown","webpage"),"attachments",false,"multiAgentWorkflows",false,"automaticCompaction",false,"scriptSkills",false,"mcp",mcp.enabled(),"maxTableRows",10000,"authentication","access-key-session","deployment","single-worker");}
    @GetMapping("/v1/agents") public Object agents(HttpServletRequest r){return w.agents(auth.current(r));}
    @PostMapping("/v1/agents") public Object saveAgent(HttpServletRequest r,@RequestBody Agent a){return w.saveAgent(auth.current(r),a,a.entityVersion);}
    @PostMapping("/v1/agents/{id}/publish") public Object publish(HttpServletRequest r,@PathVariable String id,@RequestBody Publish b){return w.publish(auth.current(r),id,b.expectedVersion());}
    @GetMapping("/v1/conversations") public Object conversations(HttpServletRequest r,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="false")boolean archived,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="30")int limit){return w.list(auth.current(r),q,archived,cursor,limit);}
    @PostMapping("/v1/conversations") public ResponseEntity<?> create(HttpServletRequest r,@RequestBody NewConversation b){return ResponseEntity.status(201).body(w.create(auth.current(r),b.clientRequestId(),b.agentRef(),b.title()));}
    @PatchMapping("/v1/conversations/{id}") public Object edit(HttpServletRequest r,@PathVariable String id,@RequestBody EditConversation b){return w.edit(auth.current(r),id,b.expectedVersion(),b.title(),b.pinned(),b.archived());}
    @DeleteMapping("/v1/conversations/{id}") public Object delete(HttpServletRequest r,@PathVariable String id){w.delete(auth.current(r),id);return map("status","deleted");}
    @GetMapping("/v1/conversations/{id}/snapshot") public Object snapshot(HttpServletRequest r,@PathVariable String id){return w.snapshot(auth.current(r),id);}
    @GetMapping("/v1/conversations/{id}/messages") public Object history(HttpServletRequest r,@PathVariable String id,@RequestParam(required=false)String before,@RequestParam(defaultValue="50")int limit){return w.history(auth.current(r),id,before,limit);}
    @PostMapping("/v1/conversations/{id}/messages") public ResponseEntity<?> send(HttpServletRequest r,@PathVariable String id,@RequestBody Send b){return ResponseEntity.accepted().body(x.send(auth.current(r),id,b.clientRequestId(),b.text(),b.contextReferences()==null?List.of():b.contextReferences()));}
    @GetMapping("/v1/requests/by-client-id/{id}") public Object receipt(HttpServletRequest r,@PathVariable String id){return w.receipt(auth.current(r),id);}
    @GetMapping("/v1/conversations/{id}/events") public Object events(HttpServletRequest r,@PathVariable String id,@RequestParam String streamId,@RequestParam String after){var es=w.events(auth.current(r),id,streamId,after);return map("events",es,"nextCursor",es.isEmpty()?after:es.get(es.size()-1).cursor());}
    @GetMapping(value="/v1/conversations/{id}/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(HttpServletRequest request,HttpServletResponse response,@PathVariable String id,@RequestParam String streamId,@RequestParam String after){Identity who=auth.current(request);w.events(who,id,streamId,after);require(streams.tryAcquire(),429,"STREAM_LIMIT","订阅数量达到限制");response.setHeader("X-Accel-Buffering","no");HttpSession session=request.getSession(false);SseEmitter emitter=new SseEmitter(120_000L);AtomicBoolean released=new AtomicBoolean();AtomicReference<ScheduledFuture<?>> future=new AtomicReference<>();String[] cursor={after};long[] heartbeat={System.nanoTime()};
        Runnable cleanup=()->{if(released.compareAndSet(false,true))streams.release();var f=future.get();if(f!=null)f.cancel(false);};emitter.onCompletion(cleanup);emitter.onTimeout(()->{cleanup.run();emitter.complete();});emitter.onError(e->cleanup.run());
        future.set(timers.scheduleWithFixedDelay(()->{if(released.get())return;try{require(session.getAttribute("identity")!=null,401,"UNAUTHENTICATED","请登录");for(var e:w.events(who,id,streamId,cursor[0])){emitter.send(SseEmitter.event().id(e.cursor()).name(e.type()).data(e));cursor[0]=e.cursor();}if(System.nanoTime()-heartbeat[0]>TimeUnit.SECONDS.toNanos(15)){emitter.send(SseEmitter.event().comment("heartbeat"));heartbeat[0]=System.nanoTime();}}catch(Exception e){try{emitter.send(SseEmitter.event().name("stream.reset").data(map("reason","refresh_snapshot")));}catch(Exception ignored){}cleanup.run();emitter.complete();}},10,250,TimeUnit.MILLISECONDS));if(released.get())future.get().cancel(false);return emitter;
    }
    @GetMapping("/v1/runs") public Object runs(HttpServletRequest r){return x.runs(auth.current(r));}
    @PostMapping("/v1/runs/{id}/cancellation-requests") public ResponseEntity<?> cancel(HttpServletRequest r,@PathVariable String id,@RequestBody Command b){return ResponseEntity.accepted().body(x.cancel(auth.current(r),id,b.clientRequestId()));}
    @PostMapping("/v1/approvals/{id}/decisions") public ResponseEntity<?> decide(HttpServletRequest r,@PathVariable String id,@RequestBody Approval b){return ResponseEntity.accepted().body(x.decide(auth.current(r),id,b.approvalRevision(),b.operationId(),b.argsHash(),b.decision(),b.clientRequestId()));}
    @PostMapping("/v1/questions/{id}/answers") public ResponseEntity<?> answer(HttpServletRequest r,@PathVariable String id,@RequestBody Answer b){return ResponseEntity.accepted().body(x.answer(auth.current(r),id,b.questionRevision(),b.text(),b.clientRequestId()));}
    @GetMapping("/v1/tools") public Object tools(){return ToolRegistry.all();}
    @GetMapping("/v1/resources") public Object resources(HttpServletRequest r,@RequestParam String kind){return w.resources(auth.current(r),kind);}
    @PostMapping("/v1/resources") public Object putResource(HttpServletRequest r,@RequestBody ResourceInput b){return w.putResource(auth.current(r),b.kind(),b.name(),b.content(),b.readers());}
    @PostMapping("/v1/knowledge/search") public Object search(HttpServletRequest r,@RequestBody Search b){return w.knowledge(auth.current(r),b.refs(),b.query());}
    @GetMapping("/v1/memories") public Object memories(HttpServletRequest r){return w.memories(auth.current(r));}
    @PostMapping("/v1/memories/{id}/decision") public Object memory(HttpServletRequest r,@PathVariable String id,@RequestBody MemoryDecision b){return w.decideMemory(auth.current(r),id,b.expectedVersion(),b.decision());}
    @GetMapping("/v1/mcp") public Object mcp(HttpServletRequest r){return mcp.catalog(auth.current(r).scope());}
    @PostMapping("/v1/mcp/{name}/discover") public Object discover(HttpServletRequest r,@PathVariable String name){Workspace.admin(auth.current(r));return mcp.discover(auth.current(r).scope(),name);}
    @GetMapping("/v1/artifacts/{id}") public Object meta(HttpServletRequest r,@PathVariable String id){return w.artifactMeta(auth.current(r),id);}
    @GetMapping("/v1/artifacts/{id}/revisions/{rev}") public Object artifact(HttpServletRequest r,@PathVariable String id,@PathVariable int rev){return w.artifactView(auth.current(r),id,rev);}
    @PostMapping("/v1/artifacts/{id}/revisions/{rev}/table-queries") public Object query(HttpServletRequest r,@PathVariable String id,@PathVariable int rev,@RequestBody TableQueryInput b){return tables.create(auth.current(r),id,rev,b.sortColumn(),b.descending(),b.filter(),b.pageSize());}
    @GetMapping("/v1/artifacts/{id}/table-queries/{qid}/rows") public Object rows(HttpServletRequest r,@PathVariable String id,@PathVariable String qid,@RequestParam(required=false)String cursor){return tables.rows(auth.current(r),id,qid,cursor);}
    @GetMapping("/v1/artifacts/{id}/table-queries/{qid}/download") public ResponseEntity<byte[]> csv(HttpServletRequest r,@PathVariable String id,@PathVariable String qid){return download(tables.csv(auth.current(r),id,qid),"table.csv","text/csv;charset=UTF-8");}
    @GetMapping("/v1/artifacts/{id}/revisions/{rev}/download") public ResponseEntity<byte[]> download(HttpServletRequest r,@PathVariable String id,@PathVariable int rev){var a=w.artifactView(auth.current(r),id,rev);var c=object(a.get("content"));if(a.get("kind").equals("markdown")){String body=String.join("\n\n",list(c.get("blocks")).stream().map(v->(String)object(v).get("markdown")).toList());return download(body,"report-v"+rev+".md","text/markdown;charset=UTF-8");}return download(canonical(a),"artifact-v"+rev+".json","application/json");}
    private static ResponseEntity<byte[]> download(String body,String name,String type){return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\""+name+"\"").header("Cache-Control","no-store").contentType(MediaType.parseMediaType(type)).body(body.getBytes(StandardCharsets.UTF_8));}
    @PreDestroy public void close(){timers.shutdownNow();}
}
