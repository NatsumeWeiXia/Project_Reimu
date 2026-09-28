package io.reimu.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reimu.core.Domain.Identity;
import io.reimu.core.Fault;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static io.reimu.core.Data.*;

/** Small deployment access-key login. Replace via enterprise identity before production rollout. */
@Component
public final class Auth extends OncePerRequestFilter {
    public record User(String token,boolean admin){}
    private final Map<String,User> users;private final String tenant,workspace,origin;private final ObjectMapper json;
    private final Map<String,long[]> attempts=new ConcurrentHashMap<>();
    public Auth(Environment env,ObjectMapper json){this.json=json;tenant=env.getProperty("reimu.tenant","local");workspace=env.getProperty("reimu.workspace","main");origin=env.getProperty("reimu.origin","http://localhost:8080");
        try{users=json.readValue(env.getRequiredProperty("reimu.users-json"),new TypeReference<Map<String,User>>(){});}catch(Exception e){throw new IllegalArgumentException("Set REIMU_USERS_JSON to named users with unique access keys",e);}
        if(users.isEmpty()||users.size()>100||users.values().stream().noneMatch(User::admin))throw new IllegalArgumentException("At least one admin and at most 100 users required");Set<String> tokens=new HashSet<>();users.forEach((name,u)->{new Identity(tenant,workspace,name,u.admin());if(u.token()==null||u.token().length()<24||!tokens.add(u.token()))throw new IllegalArgumentException("Use unique access keys of at least 24 characters");});
    }
    public Identity bootstrapIdentity(){var e=users.entrySet().stream().filter(x->x.getValue().admin()).findFirst().orElseThrow();return new Identity(tenant,workspace,e.getKey(),true);}
    public Identity current(HttpServletRequest request){return (Identity)request.getAttribute("identity");}
    public Identity login(HttpServletRequest req,String username,String token){long now=Instant.now().getEpochSecond();String key=req.getRemoteAddr();if(attempts.size()>2000)attempts.entrySet().removeIf(e->e.getValue()[0]<now-60);long[] limit=attempts.computeIfAbsent(key,k->new long[]{now,0});synchronized(limit){if(now-limit[0]>60){limit[0]=now;limit[1]=0;}Fault.require(++limit[1]<=10,429,"LOGIN_LIMIT","登录尝试过多，请稍后重试");}
        User u=users.get(username);String expected=u==null?"missing-user-no-valid-access-key":u.token();boolean matches=MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),Objects.toString(token,"").getBytes(StandardCharsets.UTF_8));Fault.require(u!=null&&matches,401,"LOGIN_FAILED","用户名或访问密钥错误");
        HttpSession previous=req.getSession(false);if(previous!=null)previous.invalidate();HttpSession session=req.getSession(true);Identity who=new Identity(tenant,workspace,username,u.admin());session.setAttribute("identity",who);session.setAttribute("csrf",id());session.setMaxInactiveInterval(1800);return who;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith("/api/")||r.getRequestURI().equals("/api/health");}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Referrer-Policy","no-referrer");res.setHeader("X-Frame-Options","DENY");
        try{boolean write=!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod());if(write)Fault.require(origin.equals(req.getHeader("Origin")),403,"ORIGIN_DENIED","请求来源未授权");
            if(req.getRequestURI().equals("/api/v1/auth/login")){chain.doFilter(req,res);return;}
            HttpSession session=req.getSession(false);Identity who=session==null?null:(Identity)session.getAttribute("identity");Fault.require(who!=null,401,"UNAUTHENTICATED","请登录");
            if(write)Fault.require(Objects.equals(session.getAttribute("csrf"),req.getHeader("X-CSRF-Token")),403,"CSRF","会话校验失败，请刷新");
            req.setAttribute("identity",who);chain.doFilter(req,res);
        }catch(Fault f){res.setStatus(f.status);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getWriter(),map("protocolVersion",1,"code",f.code,"message",f.getMessage(),"traceId",id(),"retryable",f.status==429));}
    }
}
