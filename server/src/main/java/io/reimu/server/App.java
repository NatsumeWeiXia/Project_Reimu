package io.reimu.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.reimu.core.*;
import io.reimu.core.Domain.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

@SpringBootApplication
public class App {
    private static RuntimeProvider runtime(){return ServiceLoader.load(RuntimeProvider.class).findFirst().orElseThrow(()->new IllegalStateException("AgentScope runtime module is missing; live mode never falls back to demo"));}
    public static void main(String[] args){SpringApplication.run(App.class,args);}
    @Bean Store store(JdbcTemplate jdbc,PlatformTransactionManager tx,ObjectMapper json,DataSource ds)throws Exception{return new JdbcStore(jdbc,new TransactionTemplate(tx),json,ds);}
    @Bean Workspace workspace(Store s){return new Workspace(s);}
    @Bean Execution execution(Workspace w){return new Execution(w);}
    @Bean TableQueries tables(Store s){return new TableQueries(s);}
    @Bean McpService mcp(Environment env){String config=env.getProperty("reimu.mcp-json","");return config.isBlank()?McpService.disabled():runtime().createMcp(config);}
    @Bean AgentRunner runner(Environment env,ObjectMapper json){String mode=env.getProperty("reimu.mode","live");if(mode.equals("demo"))return new DemoRunner();if(!mode.equals("live"))throw new IllegalArgumentException("reimu.mode must be live or demo");String model=env.getProperty("reimu.model-id","");if(model.isBlank())throw new IllegalArgumentException("Live mode requires REIMU_MODEL_ID and matching provider credentials");return runtime().createRunner(model);}
    @Bean ApplicationRunner initialize(Workspace w,Auth auth,Environment env){return args->{Identity admin=auth.bootstrapIdentity();if(w.agents(admin).isEmpty()){Agent a=new Agent();a.name="Reimu 通用助手";a.systemPrompt="你是严谨的业务助手。按需读取 Skill、检索知识，用工具创建产物。无证据时明确说明。记忆写入只提出候选。不要伪造工具成功。";a.model=env.getProperty("reimu.model-id","");if(a.model.isBlank())a.model="demo";a.tools=new ArrayList<>(ToolRegistry.names());if(env.getProperty("reimu.mcp-json","").isBlank())a.tools.remove("mcp_call");a=w.saveAgent(admin,a,0);w.publish(admin,a.id,a.entityVersion);}};}
}
