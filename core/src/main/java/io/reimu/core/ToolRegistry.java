package io.reimu.core;

import java.util.*;
import static io.reimu.core.Data.*;
import static io.reimu.core.Fault.*;

/** Closed-world catalog. Dynamic MCP discovery never grants a model new capabilities. */
public final class ToolRegistry {
    public record Spec(String name,String description,Map<String,Object> inputSchema,boolean approval) {}
    private static final Map<String,Spec> TOOLS=new LinkedHashMap<>();
    static {
        define("knowledge_search","在已绑定且有权访问的知识资料中检索，返回原文证据",false,map("query","string"));
        define("memory_search","读取当前用户确认的长期记忆",false,map("query","string"));
        define("memory_propose","提出一条待用户确认的长期记忆；不得自行当作已确认事实",false,map("content","string"));
        define("skill_read","读取已绑定的已发布说明型 Skill",false,map("ref","string"));
        define("artifact_create","创建独立版本化产物。kind 为 reactflow/table/markdown/webpage；content 必须符合平台 schema",false,map("kind","string","title","string","content","object"));
        define("artifact_revise","基于明确版本修订产物，不覆盖历史",false,map("artifactId","string","baseRevision","integer","kind","string","title","string","content","object"));
        define("user_ask","暂停并向用户补问",false,map("question","string"));
        define("ticket_create","在本系统创建工单，需要用户对精确参数审批；不是外部生产工单系统",true,map("title","string","body","string"));
        define("mcp_call","调用部署方配置且审核过的远程 MCP 工具，所有调用都要求审批",true,map("server","string","tool","string","arguments","object"));
    }
    private static void define(String name,String description,boolean approval,Map<String,Object> params){
        Map<String,Object> props=new LinkedHashMap<>();params.forEach((k,v)->props.put(k,map("type",v)));
        TOOLS.put(name,new Spec(name,description,map("type","object","properties",props,"required",new ArrayList<>(params.keySet()),"additionalProperties",false),approval));
    }
    public static Set<String> names(){return Collections.unmodifiableSet(TOOLS.keySet());}
    public static List<Spec> all(){return List.copyOf(TOOLS.values());}
    public static Spec spec(String name){Spec s=TOOLS.get(name);require(s!=null,422,"UNKNOWN_TOOL","工具未注册");return s;}
    public static void validate(String name,Map<String,Object> args){
        Spec s=spec(name);var props=object(s.inputSchema().get("properties"));require(args.keySet().equals(props.keySet()),422,"TOOL_ARGUMENTS","工具参数不完整或包含额外字段");
        for(var e:props.entrySet()){
            Object v=args.get(e.getKey());String type=(String)object(e.getValue()).get("type");
            switch(type){case "string"->text(v,16000,e.getKey());case "object"->object(v);case "integer"->require(v instanceof Number&&((Number)v).doubleValue()==((Number)v).longValue(),422,"TOOL_ARGUMENTS","需要整数参数");default->throw new IllegalStateException(type);}
        }
        require(canonical(args).length()<=2_100_000,413,"TOOL_ARGUMENTS","工具参数过大");
    }
}
