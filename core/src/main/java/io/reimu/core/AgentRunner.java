package io.reimu.core;

import java.io.Serializable;
import java.util.*;
import java.util.function.Consumer;
import static io.reimu.core.Domain.*;

/** A bounded SDK slice; tools run only after the slice/checkpoint/intent transaction commits. */
public interface AgentRunner {
    record ToolCall(String id,String name,Map<String,Object> arguments) implements Serializable {}
    record ToolReply(String id,String name,String text,boolean error) implements Serializable {}
    record Input(String runId,String instanceId,Identity identity,Agent agent,String text,String checkpoint,
                 List<ToolReply> replies,int step,int generation) {}
    record Outcome(String checkpoint,String text,List<ToolCall> calls,boolean complete) {}
    Outcome advance(Input input,Consumer<String> textDelta) throws Exception;
}
