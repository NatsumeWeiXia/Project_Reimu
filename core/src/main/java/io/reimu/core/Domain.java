package io.reimu.core;

import java.io.Serializable;
import java.util.*;

/** Internal entities. HTTP must use explicit projections, never return Run/checkpoint directly. */
@SuppressWarnings("serial")
public final class Domain {
    private Domain() {}
    public record Identity(String tenantId,String workspaceId,String principalId,boolean admin) implements Serializable {
        public Identity { for(String v:List.of(tenantId,workspaceId,principalId)) Fault.require(v.matches("[A-Za-z0-9_-]{1,64}"),400,"IDENTITY","身份不合法"); }
        public String scope(){return tenantId+":"+workspaceId+":"+principalId;}
        public String workspace(){return tenantId+":"+workspaceId;}
    }
    public static final class State implements Serializable {
        public int schemaVersion=1;
        public Map<String,Conversation> conversations=new LinkedHashMap<>();
        public Map<String,Run> runs=new LinkedHashMap<>();
        public Map<String,Agent> agents=new LinkedHashMap<>();
        public Map<String,Agent> agentVersions=new LinkedHashMap<>();
        public Map<String,Resource> resources=new LinkedHashMap<>();
        public Map<String,Artifact> artifacts=new LinkedHashMap<>();
        public Map<String,Memory> memories=new LinkedHashMap<>();
        public Map<String,Operation> operations=new LinkedHashMap<>();
        public Map<String,Question> questions=new LinkedHashMap<>();
        public Map<String,Receipt> receipts=new LinkedHashMap<>();
        public Map<String,TableQuery> tableQueries=new LinkedHashMap<>();
        public Map<String,String> tickets=new LinkedHashMap<>();
        public List<Map<String,Object>> audit=new ArrayList<>();
    }
    public static final class Conversation implements Serializable {
        public String id,scope,title,agentRef,instanceId=Data.id(),checkpoint="",createdAt=Data.now(),updatedAt=createdAt;
        public int entityVersion=1; public boolean pinned,archived,deleted; public long sequence,messageSequence;
        public String activeRunId="";
        public List<Message> messages=new ArrayList<>(); public List<Event> events=new ArrayList<>();
    }
    public static final class Message implements Serializable {
        public String id=Data.id(),conversationId,ordinal,role,status="complete",createdAt=Data.now(),runId,clientRequestId;
        public int entityVersion=1;
        public List<Map<String,Object>> parts=new ArrayList<>();
    }
    public static final class Agent implements Serializable {
        public String id,workspace,name,systemPrompt,model="default"; public int revision,entityVersion=1,maxSteps=16;
        public List<String> tools=new ArrayList<>(),skills=new ArrayList<>(),knowledge=new ArrayList<>();
    }
    public static final class Resource implements Serializable {
        public String id,workspace,owner,kind,name,content,hash,createdAt=Data.now(); public int revision=1;
        public boolean published; public List<String> readers=new ArrayList<>();
    }
    public static final class Run implements Serializable {
        public String id=Data.id(),conversationId,scope,agentRef,instanceId,state="queued",input,checkpoint="",assistantId,attemptId;
        public int step,generation,budget=16; public boolean cancelRequested;
        public String errorCode="",createdAt=Data.now(),updatedAt=createdAt;
        public List<String> operationIds=new ArrayList<>();
        public List<Map<String,Object>> contextReferences=new ArrayList<>();
        public List<AgentRunner.ToolReply> replies=new ArrayList<>();
    }
    public static final class Operation implements Serializable {
        public String id=Data.id(),runId,scope,toolName,toolCallId,argsHash,state="prepared",result="",approvalId,decision="pending",expiresAt;
        public String targetSummary; public int entityVersion=1;
        public Map<String,Object> args=new LinkedHashMap<>();
    }
    public static final class Question implements Serializable {
        public String id=Data.id(),runId,scope,operationId,text,status="open",answer=""; public int entityVersion=1;
    }
    public static final class Artifact implements Serializable {
        public String id=Data.id(),conversationId,scope,kind,title; public boolean revoked;
        public List<Revision> revisions=new ArrayList<>();
    }
    public static final class Revision implements Serializable {
        public int revision; public String sourceRunId,hash,createdAt=Data.now(),summary="";
        public Map<String,Object> content=new LinkedHashMap<>();
    }
    public static final class Memory implements Serializable {
        public String id=Data.id(),scope,content,sourceConversationId,sourceMessageId,status="candidate",createdAt=Data.now(),expiresAt;
        public int entityVersion=1;
    }
    public record Receipt(String clientRequestId,String bodyHash,Map<String,Object> response) implements Serializable {}
    public record Event(int protocolVersion,String eventId,String streamId,String conversationId,String workspaceId,
                        String permissionVersion,String cursor,String previousCursor,String occurredAt,String type,Map<String,Object> data) implements Serializable {}
    public static final class TableQuery implements Serializable {
        public String id=Data.id(),scope,artifactId,asOf=Data.now(),expiresAt;public int revision,pageSize=50;
        public List<Map<String,Object>> rows=new ArrayList<>();
    }
}
