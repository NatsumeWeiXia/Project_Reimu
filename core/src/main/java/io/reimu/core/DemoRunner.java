package io.reimu.core;

import java.util.*;
import java.util.function.Consumer;
import static io.reimu.core.Data.*;

/** Deterministic integration fixture, enabled only by an explicit demo profile. Not an LLM. */
public final class DemoRunner implements AgentRunner {
    public Outcome advance(Input input,Consumer<String> delta){
        if(input.step()>0){String text="演示执行已结束。工具回执已保存；点击上方产物卡片可查看，打开预览不会再次执行模型。";delta.accept(text);return new Outcome(input.checkpoint(),text,List.of(),true);}
        String q=input.text();List<ToolCall> calls=new ArrayList<>();
        if(q.contains("补问"))calls.add(new ToolCall(id(),"user_ask",map("question","请补充你要分析的业务范围。")));
        else if(q.contains("工单"))calls.add(new ToolCall(id(),"ticket_create",map("title","演示工单","body","用户明确请求的 Reimu 内部工单；不连接外部生产系统。")));
        else if(q.contains("记住"))calls.add(new ToolCall(id(),"memory_propose",map("content",q.substring(q.indexOf("记住")+2).strip())));
        else if(q.contains("知识"))calls.add(new ToolCall(id(),"knowledge_search",map("query",q)));
        else if(!input.checkpoint().isBlank()&&q.contains("上一条")){String text="演示记录中的上一条输入："+input.checkpoint();delta.accept(text);return new Outcome(q,text,List.of(),true);}
        else if(q.contains("流程")||q.contains("表格")||q.contains("演示")||q.contains("报告")){
            calls.add(new ToolCall(id(),"artifact_create",map("kind","reactflow","title","处理流程 · 演示数据","content",map("graphSchema","reimu.workflow.v1","purpose","explanatory","nodes",List.of(
                    map("id","n1","type","start","label","接收需求","position",map("x",40,"y",100),"data",map()),
                    map("id","n2","type","step","label","寻找证据","position",map("x",310,"y",100),"data",map()),
                    map("id","n3","type","end","label","验证结果","position",map("x",580,"y",100),"data",map())),"edges",List.of(map("id","e1","source","n1","target","n2"),map("id","e2","source","n2","target","n3"))))));
            calls.add(new ToolCall(id(),"artifact_create",map("kind","table","title","分析表 · 演示数据","content",map("mode","snapshot","columns",List.of(map("id","item","title","检查项","dataType","string","nullable",false),map("id","value","title","示例编号","dataType","integerString","nullable",false)),"rows",List.of(map("rowId","r1","values",map("item","指标核对","value","9007199254740993")),map("rowId","r2","values",map("item","日志核对","value","9007199254740994")))))));
            calls.add(new ToolCall(id(),"artifact_create",map("kind","markdown","title","诊断报告 · 演示数据","content",map("blocks",List.of(map("blockId","b1","markdown","# 演示报告\n\n此内容用于验证工作台，不是真实业务结论。"),map("blockId","b2","markdown","## 下一步\n\n1. 采集实际指标。\n2. 查找可核对的证据。\n3. 通过审批执行变更并复验。")),"evidenceIds",List.of()))));
            calls.add(new ToolCall(id(),"artifact_create",map("kind","webpage","title","AgentScope 文档","content",map("mode","external_only","url","https://java.agentscope.io/","title","AgentScope 文档","reason","外部网站是否可嵌入未经确认，使用新标签页打开"))));
        }
        String text=calls.isEmpty()?"这是显式演示模式，不会调用大模型。可以输入“生成流程、表格和报告”“创建工单”或“请补问”验证流程。":"已准备演示产物或操作，正在交给持久化工具网关处理。";
        delta.accept(text);return new Outcome(q,text,calls,calls.isEmpty());
    }
}
