package io.reimu.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.reimu.core.Domain.State;
import io.reimu.core.Store;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.util.function.Function;

/** Small-deployment aggregate store. Deliberately NOT a multi-worker/large-volume schema. */
public final class JdbcStore implements Store {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    private final ThreadLocal<State> active=new ThreadLocal<>();private final Connection processLock;
    public JdbcStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,DataSource source) throws Exception {
        this.jdbc=jdbc;this.tx=tx;this.json=json;
        // H2 defaults to delayed durable writes. Tool intent commits must survive an immediate
        // process kill, not only a graceful shutdown. This is not a hardware power-loss guarantee.
        try(Connection probe=source.getConnection()){
            if(probe.getMetaData().getDatabaseProductName().equals("H2"))jdbc.execute("SET WRITE_DELAY 0");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS reimu_state (id INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL, content TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS reimu_process_guard (id INTEGER PRIMARY KEY)");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM reimu_state",Integer.class)==0)jdbc.update("INSERT INTO reimu_state VALUES(1,1,?)",encode(new State()));
        if(jdbc.queryForObject("SELECT COUNT(*) FROM reimu_process_guard",Integer.class)==0)jdbc.update("INSERT INTO reimu_process_guard VALUES(1)");
        // Lifetime DB row lock prevents accidentally starting another worker against this DB.
        processLock=source.getConnection();processLock.setAutoCommit(false);
        try(var statement=processLock.createStatement()){
            String db=processLock.getMetaData().getDatabaseProductName();
            if(db.equals("PostgreSQL"))statement.execute("SET lock_timeout = '1500ms'");else if(db.equals("H2"))statement.execute("SET LOCK_TIMEOUT 1500");
            else throw new IllegalStateException("Only PostgreSQL or H2 are supported");
            try(var ignored=statement.executeQuery("SELECT id FROM reimu_process_guard WHERE id=1 FOR UPDATE")){if(!ignored.next())throw new IllegalStateException("Missing worker guard");}
        }catch(Exception e){processLock.close();throw new IllegalStateException("Cannot acquire single-worker database lock",e);}
    }
    private State decode(String text){try{State s=json.readValue(text,State.class);if(s.schemaVersion!=1)throw new IllegalStateException("Unsupported state schema");return s;}catch(IOException e){throw new IllegalStateException("Database snapshot is invalid",e);}}
    private String encode(State s){try{return json.writeValueAsString(s);}catch(IOException e){throw new IllegalStateException("State encoding failed",e);}}
    @Override public <T>T read(Function<State,T> action){State nested=active.get();if(nested!=null)return action.apply(decode(encode(nested)));return action.apply(decode(jdbc.queryForObject("SELECT content FROM reimu_state WHERE id=1",String.class)));}
    @Override public <T>T write(Function<State,T> action){if(active.get()!=null)throw new IllegalStateException("Nested writes are prohibited");return tx.execute(status->{State s=decode(jdbc.queryForObject("SELECT content FROM reimu_state WHERE id=1 FOR UPDATE",String.class));active.set(s);try{T out=action.apply(s);String data=encode(s);if(data.length()>32_000_000)throw new IllegalStateException("Aggregate capacity reached; archive or migrate storage");jdbc.update("UPDATE reimu_state SET content=? WHERE id=1",data);return out;}finally{active.remove();}});}
    @Override public void close() throws IOException {try{processLock.rollback();processLock.close();}catch(java.sql.SQLException e){throw new IOException(e);}}
}
