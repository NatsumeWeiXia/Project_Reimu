package io.reimu.server;

import io.reimu.core.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

/** One durable dispatcher. SDK calls run outside transactions; bounded and cancellable. */
@Component
public final class Worker {
    private final Execution execution;private final AgentRunner runner;private final McpService mcp;
    private final ExecutorService loop=Executors.newSingleThreadExecutor(),model=Executors.newSingleThreadExecutor();private volatile boolean closed;
    public Worker(Execution execution,AgentRunner runner,McpService mcp){this.execution=execution;this.runner=runner;this.mcp=mcp;}
    @EventListener(ApplicationReadyEvent.class) public void start(){execution.recover();loop.submit(this::work);}
    private void work(){while(!closed){try{
        var operation=execution.claimOperation();if(operation.isPresent()){var o=operation.get();if(o.toolName.equals("mcp_call")){var reply=mcp.execute(o);execution.finishOperation(o.id,reply.content(),reply.error(),reply.unknown());}else{try{execution.executeLocal(o.id);}catch(Exception e){execution.finishOperation(o.id,e instanceof Fault f?f.code+": "+f.getMessage():"本地工具失败，事务已回滚",true,false);}}continue;}
        var next=execution.claim();if(next.isEmpty()){Thread.sleep(200);continue;}var input=next.get();Future<AgentRunner.Outcome> future=model.submit(()->runner.advance(input,d->execution.delta(input,d)));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        try{while(true){try{var result=future.get(250,TimeUnit.MILLISECONDS);execution.commit(input,result);break;}catch(TimeoutException e){if(execution.cancellationRequested(input.runId(),input.generation())){future.cancel(true);execution.failed(input,"CANCELLED");break;}if(System.nanoTime()>deadline){future.cancel(true);execution.failed(input,"MODEL_TIMEOUT");break;}}}}catch(Exception e){future.cancel(true);Throwable cause=e instanceof ExecutionException?e.getCause():e;execution.failed(input,cause instanceof Fault f?f.code:"MODEL_FAILED");}
    }catch(InterruptedException e){Thread.currentThread().interrupt();return;}catch(Exception e){System.err.println("Reimu dispatcher error: "+e.getClass().getSimpleName()+"; no request content logged");try{Thread.sleep(1000);}catch(InterruptedException stop){Thread.currentThread().interrupt();return;}}}}
    @PreDestroy public void close(){closed=true;loop.shutdownNow();model.shutdownNow();}
}
