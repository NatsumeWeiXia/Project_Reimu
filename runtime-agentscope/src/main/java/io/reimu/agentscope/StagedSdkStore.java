package io.reimu.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.util.*;

/** SDK writes remain staged until the platform commits its Outcome and tool intents atomically. */
public final class StagedSdkStore implements AgentStateStore {
    private final String user,session;private final ObjectMapper json;private final Map<String,List<String>> entries;private boolean saved;
    public StagedSdkStore(String user,String session,String checkpoint,ObjectMapper json){this.user=user;this.session=session;this.json=json;
        try{entries=checkpoint.isBlank()?new LinkedHashMap<>():json.readValue(checkpoint,new TypeReference<Map<String,List<String>>>(){});}catch(Exception e){throw new IllegalStateException("SDK checkpoint format incompatible",e);}}
    private void scope(String u,String s){if(!Objects.equals(user,u)||!Objects.equals(session,s))throw new SecurityException("SDK namespace mismatch");}
    @Override public synchronized void save(String u,String s,String key,State value){scope(u,s);entries.put(key,List.of(JsonUtils.getJsonCodec().toJson(value)));saved=true;}
    @Override public synchronized void save(String u,String s,String key,List<? extends State> values){scope(u,s);entries.put(key,values.stream().map(v->JsonUtils.getJsonCodec().toJson(v)).toList());saved=true;}
    @Override public synchronized <T extends State>Optional<T> get(String u,String s,String key,Class<T> type){scope(u,s);var values=entries.get(key);return values==null||values.isEmpty()?Optional.empty():Optional.of(JsonUtils.getJsonCodec().fromJson(values.get(0),type));}
    @Override public synchronized <T extends State>List<T> getList(String u,String s,String key,Class<T> type){scope(u,s);return entries.getOrDefault(key,List.of()).stream().map(v->JsonUtils.getJsonCodec().fromJson(v,type)).toList();}
    @Override public synchronized boolean exists(String u,String s){scope(u,s);return !entries.isEmpty();}
    @Override public synchronized void delete(String u,String s){scope(u,s);entries.clear();saved=true;}
    @Override public synchronized void delete(String u,String s,String key){scope(u,s);entries.remove(key);saved=true;}
    @Override public synchronized Set<String> listSessionIds(String u){if(!user.equals(u))throw new SecurityException("SDK namespace mismatch");return entries.isEmpty()?Set.of():Set.of(session);}
    public synchronized String checkpoint(){if(!saved)throw new IllegalStateException("SDK did not save a checkpoint; refusing to dispatch tools");try{String out=json.writeValueAsString(entries);if(out.length()>8_000_000)throw new IllegalStateException("Context capacity reached; start a new conversation");return out;}catch(java.io.IOException e){throw new IllegalStateException(e);}}
}
