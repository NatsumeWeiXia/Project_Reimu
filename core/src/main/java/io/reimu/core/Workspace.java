package io.reimu.core;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static io.reimu.core.Domain.*;
import static io.reimu.core.Data.*;
import static io.reimu.core.Fault.*;

/** Platform-owned conversation, resource, evidence, memory and artifact authority. */
public final class Workspace {
    public final Store store;
    public Workspace(Store store){this.store=store;}
    public static String receiptKey(Identity who,String key){return who.scope()+":"+text(key,120,"clientRequestId");}
    public static <T> T visible(T value,boolean ok){require(value!=null&&ok,404,"RESOURCE_NOT_AVAILABLE","资源不存在或无权访问");return value;}
    public static Conversation conversation(State s,Identity who,String id){var c=s.conversations.get(id);return visible(c,c!=null&&!c.deleted&&who.scope().equals(c.scope));}
    public static Artifact artifact(State s,Identity who,String id){var a=s.artifacts.get(id);return visible(a,a!=null&&!a.revoked&&who.scope().equals(a.scope)&&!s.conversations.get(a.conversationId).deleted);}
    public static void admin(Identity who){require(who.admin(),403,"FORBIDDEN","需要管理员权限");}
    public static String cursor(long n){return "p"+Long.toString(n,36);}
    public static String streamId(Conversation c){return "s-"+c.id+"-1";}
    public static void emit(Conversation c,Identity who,String type,Map<String,Object> data){
        String before=cursor(c.sequence);c.sequence++;
        c.events.add(new Event(1,id(),streamId(c),c.id,who.workspaceId(),"1",cursor(c.sequence),before,now(),type,Data.copy(new LinkedHashMap<>(data))));
        if(c.events.size()>2000)c.events.remove(0);
        c.updatedAt=now();
    }
    public static Message addMessage(Conversation c,String role,String text,String runId,String clientId){
        Message m=new Message();m.conversationId=c.id;m.ordinal=Long.toString(++c.messageSequence);m.role=role;m.runId=runId;m.clientRequestId=clientId;
        if(text!=null&&!text.isEmpty())m.parts.add(map("type","text","id",id(),"text",text));c.messages.add(m);return m;
    }
    public static Map<String,Object> messageView(Message m){return map("id",m.id,"conversationId",m.conversationId,"ordinal",m.ordinal,"entityVersion",m.entityVersion,"role",m.role,"status",m.status,"parts",m.parts,"createdAt",m.createdAt,"runId",m.runId,"clientRequestId",m.clientRequestId);}
    public static void changed(Conversation c,Identity who,Message m){m.entityVersion++;emit(c,who,"message.upsert",map("message",messageView(m)));}
    public static Map<String,Object> summary(Conversation c){return map("id",c.id,"title",c.title,"entityVersion",c.entityVersion,"entry",map("kind","agent","ref",c.agentRef),"pinned",c.pinned,"archived",c.archived,"createdAt",c.createdAt,"updatedAt",c.updatedAt);}
    public Map<String,Object> create(Identity who,String clientId,String agentRef,String title){
        var body=map("command","conversation.create","agentRef",agentRef,"title",title);return store.write(s->{
            var previous=s.receipts.get(receiptKey(who,clientId));if(previous!=null){require(previous.bodyHash().equals(hash(body)),409,"IDEMPOTENCY_CONFLICT","请求标识已用于不同内容");return previous.response();}
            Agent a=s.agentVersions.get(agentRef);visible(a,a!=null&&who.workspace().equals(a.workspace));
            Conversation c=new Conversation();c.id=id();c.scope=who.scope();c.title=text(title,120,"title");c.agentRef=agentRef;
            s.conversations.put(c.id,c);var result=summary(c);s.receipts.put(receiptKey(who,clientId),new Receipt(clientId,hash(body),result));return result;
        });
    }
    public Map<String,Object> receipt(Identity who,String clientId){return store.read(s->{var r=s.receipts.get(receiptKey(who,clientId));visible(r,r!=null);return r.response();});}
    public Map<String,Object> list(Identity who,String q,boolean archived,String after,int limit){
        int size=Math.max(1,Math.min(100,limit));String query=q==null?"":q.toLowerCase(Locale.ROOT);
        return store.read(s->{var cs=s.conversations.values().stream().filter(c->!c.deleted&&c.scope.equals(who.scope())&&c.archived==archived&&c.title.toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.<Conversation,Boolean>comparing(c->c.pinned).reversed().thenComparing(c->c.createdAt,Comparator.reverseOrder()).thenComparing(c->c.id)).toList();
            int start=0;if(after!=null&&!after.isBlank()){start=-1;for(int i=0;i<cs.size();i++)if(cs.get(i).id.equals(after)){start=i+1;break;}require(start>=0,410,"CURSOR_EXPIRED","目录游标已失效，请刷新");}
            int end=Math.min(cs.size(),start+size);return map("items",cs.subList(start,end).stream().map(Workspace::summary).toList(),"nextCursor",end<cs.size()?cs.get(end-1).id:null);
        });
    }
    public Map<String,Object> edit(Identity who,String id,int expected,String title,Boolean pinned,Boolean archived){return store.write(s->{var c=conversation(s,who,id);
        require(c.entityVersion==expected,409,"VERSION_CONFLICT","会话已被修改");if(title!=null)c.title=text(title,120,"title");if(pinned!=null)c.pinned=pinned;if(archived!=null)c.archived=archived;c.entityVersion++;
        emit(c,who,"conversation.updated",map("conversation",summary(c)));return summary(c);});}
    public void delete(Identity who,String id){store.write(s->{var c=conversation(s,who,id);require(c.activeRunId.isEmpty(),409,"RUN_ACTIVE","请先停止当前任务");
        c.deleted=true;c.messages.clear();c.events.clear();c.checkpoint="";
        s.artifacts.values().stream().filter(a->a.conversationId.equals(id)).forEach(a->{a.revoked=true;a.revisions.clear();});
        s.tableQueries.values().removeIf(q->s.artifacts.containsKey(q.artifactId)&&s.artifacts.get(q.artifactId).conversationId.equals(id));
        s.runs.values().stream().filter(r->r.conversationId.equals(id)).forEach(r->{r.input="";r.checkpoint="";r.replies.clear();r.contextReferences.clear();});
        s.audit.add(map("type","conversation.deleted","scope",who.scope(),"id",id,"at",now()));return null;});}
    public Map<String,Object> snapshot(Identity who,String id){return store.read(s->{var c=conversation(s,who,id);var messages=c.messages.subList(Math.max(0,c.messages.size()-50),c.messages.size());
        return map("protocolVersion",1,"conversation",summary(c),"streamId",streamId(c),"cursor",cursor(c.sequence),"permissionVersion","1","workspaceId",who.workspaceId(),
                "messages",messages.stream().map(Workspace::messageView).toList(),"hasMore",c.messages.size()>50,
                "activity",activity(s,c),"tools",s.operations.values().stream().filter(o->c.id.equals(s.runs.get(o.runId).conversationId)).map(Workspace::operationView).toList(),
                "approvals",s.operations.values().stream().filter(o->o.approvalId!=null&&c.id.equals(s.runs.get(o.runId).conversationId)).map(Workspace::approvalView).toList(),
                "questions",s.questions.values().stream().filter(x->c.id.equals(s.runs.get(x.runId).conversationId)).map(Workspace::questionView).toList());});}
    public static Map<String,Object> activity(State s,Conversation c){
        Run r=s.runs.get(c.activeRunId);String state=r==null?"idle":switch(r.state){case "queued"->"queued";case "running"->"generating";case "waiting_approval"->"waiting_approval";case "waiting_user"->"waiting_user";case "cancelling"->"cancelling";case "paused"->"paused_budget";case "needs_attention"->"failed";default->"working";};
        return map("state",state,"sendMode",r==null?"chat":"disabled","runId",r==null?null:r.id,"canRequestCancellation",r!=null,"label",r==null?"等待你的下一条消息":state);
    }
    public Map<String,Object> history(Identity who,String id,String before,int limit){return store.read(s->{var c=conversation(s,who,id);long n=before==null?Long.MAX_VALUE:parseOrdinal(before);int size=Math.max(1,Math.min(limit,100));
        var all=c.messages.stream().filter(m->Long.parseLong(m.ordinal)<n).toList();return map("items",all.subList(Math.max(0,all.size()-size),all.size()).stream().map(Workspace::messageView).toList(),"hasMore",all.size()>size);});}
    private static long parseOrdinal(String n){try{return Long.parseLong(n);}catch(NumberFormatException e){throw new Fault(422,"CURSOR","消息游标错误");}}
    public List<Event> events(Identity who,String id,String stream,String after){return store.read(s->{var c=conversation(s,who,id);require(streamId(c).equals(stream),410,"CURSOR_EXPIRED","投影已改变");
        if(cursor(c.sequence).equals(after))return List.of();int index=-1;for(int i=0;i<c.events.size();i++)if(c.events.get(i).previousCursor().equals(after)){index=i;break;}
        require(index>=0,410,"CURSOR_EXPIRED","事件已过期，请重新获取快照");return new ArrayList<>(c.events.subList(index,Math.min(index+500,c.events.size())));});}
    public List<Agent> agents(Identity who){return store.read(s->s.agents.values().stream().filter(a->a.workspace.equals(who.workspace())).toList());}
    public Agent saveAgent(Identity who,Agent input,int expected){admin(who);text(input.name,100,"name");text(input.systemPrompt,16000,"systemPrompt");
        require(input.maxSteps>=1&&input.maxSteps<=100,422,"BUDGET","步数必须在 1–100 之间");
        require(input.tools!=null&&input.skills!=null&&input.knowledge!=null,422,"VALIDATION","能力列表不能为空");
        require(ToolRegistry.names().containsAll(input.tools),422,"UNKNOWN_TOOL","存在未注册的工具");
        return store.write(s->{Agent old=input.id==null?null:s.agents.get(input.id);if(old!=null){visible(old,old.workspace.equals(who.workspace()));require(old.entityVersion==expected,409,"VERSION_CONFLICT","Agent 草稿已修改");}
            Agent a=copy(input);a.id=old==null?id():old.id;a.workspace=who.workspace();a.revision=old==null?0:old.revision;a.entityVersion=old==null?1:old.entityVersion+1;s.agents.put(a.id,a);return a;});}
    public Agent publish(Identity who,String id,int expected){admin(who);return store.write(s->{Agent a=s.agents.get(id);visible(a,a!=null&&a.workspace.equals(who.workspace()));require(a.entityVersion==expected,409,"VERSION_CONFLICT","草稿版本冲突");
        for(String ref:a.skills)resource(s,who,ref,"skill");for(String ref:a.knowledge)resource(s,who,ref,"knowledge");
        a.revision++;a.entityVersion++;Agent published=copy(a);s.agentVersions.put(id+"@"+a.revision,published);return published;});}
    public Resource putResource(Identity who,String kind,String name,String content,List<String> readers){admin(who);require(Set.of("knowledge","skill").contains(kind),422,"RESOURCE_KIND","资源类型不支持");
        text(name,100,"name");text(content,500_000,"content");require(readers!=null&&readers.size()<=100,422,"READERS","读取者数量错误");
        if(kind.equals("skill"))require(content.startsWith("---\n")&&content.contains("\nname:")&&content.contains("\ndescription:"),422,"SKILL_MANIFEST","Skill 必须含 name/description frontmatter；首版只允许说明型 Skill");
        return store.write(s->{Resource r=new Resource();r.id=id();r.workspace=who.workspace();r.owner=who.principalId();r.kind=kind;r.name=name;r.content=content;r.hash=hash(content);r.readers=new ArrayList<>(readers);r.published=true;s.resources.put(r.id+"@1",r);return r;});}
    public static Resource resource(State s,Identity who,String ref,String kind){Resource r=s.resources.get(ref);return visible(r,r!=null&&r.published&&r.kind.equals(kind)&&r.workspace.equals(who.workspace())&&(r.owner.equals(who.principalId())||r.readers.contains(who.principalId())||r.readers.contains("*")));}
    public List<Resource> resources(Identity who,String kind){return store.read(s->s.resources.values().stream().filter(r->r.kind.equals(kind)&&r.workspace.equals(who.workspace())&&r.published&&(r.owner.equals(who.principalId())||r.readers.contains(who.principalId())||r.readers.contains("*"))).toList());}
    public List<Map<String,Object>> knowledge(Identity who,List<String> refs,String query){text(query,2000,"query");return store.read(s->{List<Map<String,Object>> result=new ArrayList<>();Set<String> terms=new HashSet<>();String q=query.toLowerCase(Locale.ROOT);for(String t:q.split("[^\\p{L}\\p{N}]+"))if(!t.isBlank())terms.add(t);
        for(int i=0;i+1<q.length()&&terms.size()<40;i++)if(Character.UnicodeScript.of(q.charAt(i))==Character.UnicodeScript.HAN)terms.add(q.substring(i,i+2));
        for(String ref:refs){Resource r=resource(s,who,ref,"knowledge");String[] lines=r.content.split("\n",-1);for(int i=0;i<lines.length;i+=8){String chunk=String.join("\n",Arrays.copyOfRange(lines,i,Math.min(i+8,lines.length)));long score=terms.stream().filter(t->chunk.toLowerCase(Locale.ROOT).contains(t)).count();if(score>0)result.add(map("evidenceId",r.id+"@"+r.revision+":"+(i+1),"resourceRef",ref,"title",r.name,"startLine",i+1,"text",chunk,"score",score));}}
        result.sort(Comparator.comparingLong(x->-((Number)x.get("score")).longValue()));return result.stream().limit(6).toList();});}
    public Memory proposeMemory(Identity who,String cid,String mid,String content){text(content,2000,"memory");return store.write(s->{var c=conversation(s,who,cid);require(c.messages.stream().anyMatch(m->m.id.equals(mid)),422,"MEMORY_SOURCE","记忆来源不存在");Memory m=new Memory();m.scope=who.scope();m.content=content;m.sourceConversationId=cid;m.sourceMessageId=mid;s.memories.put(m.id,m);return m;});}
    public List<Memory> memories(Identity who){return store.read(s->s.memories.values().stream().filter(m->m.scope.equals(who.scope())&&!m.status.equals("deleted")).toList());}
    public Memory decideMemory(Identity who,String id,int expected,String decision){require(Set.of("active","deleted").contains(decision),422,"MEMORY_DECISION","记忆操作错误");return store.write(s->{require(s.conversations.values().stream().noneMatch(c->c.scope.equals(who.scope())&&!c.activeRunId.isEmpty()),409,"RUN_ACTIVE","请先停止活动任务，再确认或遗忘记忆");Memory m=s.memories.get(id);visible(m,m!=null&&m.scope.equals(who.scope())&&!m.status.equals("deleted"));require(m.entityVersion==expected,409,"VERSION_CONFLICT","记忆版本冲突");m.status=decision;m.entityVersion++;if(decision.equals("deleted"))m.content="";
        // Memory withdrawal cannot erase downloaded text. Reset future model contexts to avoid retained injection.
        s.conversations.values().stream().filter(c->c.scope.equals(who.scope())).forEach(c->c.checkpoint="");
        return m;});}
    public Map<String,Object> artifactView(Identity who,String id,int revision){return store.read(s->{var a=artifact(s,who,id);require(revision>=1&&revision<=a.revisions.size(),404,"RESOURCE_NOT_AVAILABLE","版本不可用");return revisionView(a,a.revisions.get(revision-1),true);});}
    public Map<String,Object> artifactMeta(Identity who,String id){return store.read(s->{var a=artifact(s,who,id);return map("artifactId",a.id,"title",a.title,"kind",a.kind,"revisions",a.revisions.stream().map(r->map("revision",r.revision,"createdAt",r.createdAt,"summary",r.summary)).toList());});}
    public static Map<String,Object> revisionView(Artifact a,Revision r,boolean content){
        Map<String,Object> view=map("artifactId",a.id,"conversationId",a.conversationId,"revision",r.revision,"kind",a.kind,"schemaVersion",1,"title",a.title,"sourceRunId",r.sourceRunId,"contentHash",r.hash,"createdAt",r.createdAt,
                "permissions",map("canView",true,"canDownload",true,"canReference",true,"canRequestRevision",true));
        if(content){Map<String,Object> c=new LinkedHashMap<>(r.content);if(a.kind.equals("table")){c.remove("rows");c.put("queryEndpoint","/api/v1/artifacts/"+a.id+"/revisions/"+r.revision+"/table-queries");}view.put("content",c);}return view;
    }
    static Artifact createArtifact(State s,Identity who,Run run,String kind,String title,Map<String,Object> content,String existing,int base){
        ArtifactRules.validate(kind,content);text(title,150,"title");var c=conversation(s,who,run.conversationId);Artifact a;
        if(existing==null||existing.isBlank()){a=new Artifact();a.conversationId=c.id;a.scope=who.scope();a.kind=kind;a.title=title;s.artifacts.put(a.id,a);}
        else{a=artifact(s,who,existing);require(a.conversationId.equals(c.id)&&a.kind.equals(kind),422,"ARTIFACT_CONTEXT","产物不属于当前会话或类型不匹配");require(a.revisions.size()==base,409,"VERSION_CONFLICT","基础版本已过时，未覆盖新版本");}
        Revision r=new Revision();r.revision=a.revisions.size()+1;r.sourceRunId=run.id;r.content=content;r.hash=hash(content);a.revisions.add(r);
        Message msg=c.messages.stream().filter(m->m.id.equals(run.assistantId)).findFirst().orElseThrow();
        msg.parts.add(map("type","artifact_ref","id",id(),"artifactId",a.id,"revision",r.revision,"kind",a.kind,"title",a.title));
        emit(c,who,"artifact.available",map("artifact",revisionView(a,r,false)));changed(c,who,msg);return a;
    }
    public static void validateReferences(State s,Identity who,Conversation c,List<Map<String,Object>> refs){
        require(refs!=null&&refs.size()<=5,422,"REFERENCE_LIMIT","最多引用 5 个产物");
        for(var ref:refs){Artifact a=artifact(s,who,text(ref.get("artifactId"),100,"artifactId"));require(a.conversationId.equals(c.id),422,"REFERENCE_SCOPE","不能隐式引用其他会话产物");
            require(ref.get("revision") instanceof Number,422,"REFERENCE_REVISION","引用缺少版本");double raw=((Number)ref.get("revision")).doubleValue();require(Double.isFinite(raw)&&raw==Math.rint(raw)&&raw<=Integer.MAX_VALUE,422,"REFERENCE_REVISION","引用版本必须是正整数");int n=(int)raw;require(n>0&&n<=a.revisions.size(),422,"REFERENCE_REVISION","引用版本不存在");
            var selected=object(ref.get("selection"));ArtifactRules.selection(a.revisions.get(n-1).content,a.kind,selected);
            if(selected.get("type").equals("table_rows")&&selected.containsKey("querySnapshotId")){
                TableQuery q=s.tableQueries.get(text(selected.get("querySnapshotId"),100,"querySnapshotId"));
                visible(q,q!=null&&q.scope.equals(who.scope())&&q.artifactId.equals(a.id)&&q.revision==n);
                require(Instant.parse(q.expiresAt).isAfter(Instant.now()),410,"QUERY_EXPIRED","所引用的查询快照已过期");
                var allowed=q.rows.stream().map(row->row.get("rowId")).toList();require(allowed.containsAll(Data.list(selected.get("rowIds"))),422,"REFERENCE_SELECTION","所选行不属于该查询结果");
            }
        }
    }
    public static Map<String,Object> operationView(Operation o){return map("operationId",o.id,"entityVersion",o.entityVersion,"toolName",o.toolName,"state",o.state,"summary",o.targetSummary);}
    public static Map<String,Object> approvalView(Operation o){return map("approvalId",o.approvalId,"runId",o.runId,"entityVersion",o.entityVersion,"operationId",o.id,"toolVersion",o.toolName+"@1","argsHash",o.argsHash,"targetSummary",o.targetSummary,"parameterSummary",canonical(o.args),"riskSummary","此操作需要确认；批准不表示执行成功","expiresAt",o.expiresAt,"status",o.decision,"canDecide",o.decision.equals("pending"));}
    public static Map<String,Object> questionView(Question q){return map("questionId",q.id,"runId",q.runId,"entityVersion",q.entityVersion,"text",q.text,"status",q.status,"canAnswer",q.status.equals("open"));}
    public static Identity identityOf(String scope){String[] p=scope.split(":",-1);return new Identity(p[0],p[1],p[2],false);}
    public static Map<String,Object> runView(Run r){return map("id",r.id,"conversationId",r.conversationId,"state",r.state,"steps",r.step,"budget",r.budget,"errorCode",r.errorCode,"cancelRequested",r.cancelRequested,"createdAt",r.createdAt);}
}
