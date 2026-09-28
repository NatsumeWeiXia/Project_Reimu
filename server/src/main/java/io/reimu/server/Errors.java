package io.reimu.server;
import io.reimu.core.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public final class Errors {
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    void disconnected(){ /* The peer closed SSE. Never try to write JSON into a closed stream. */ }
    @ExceptionHandler(Fault.class) ResponseEntity<?> fault(Fault f){return ResponseEntity.status(f.status).body(Data.map("protocolVersion",1,"code",f.code,"message",f.getMessage(),"traceId",Data.id(),"retryable",f.status==429));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ResponseEntity<?> invalid(Exception e){return fault(new Fault(422,"VALIDATION","请求结构或参数类型不正确"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unknown(Exception e){String trace=Data.id();System.err.println("Reimu API error "+trace+" "+e.getClass().getSimpleName());return ResponseEntity.status(500).body(Data.map("protocolVersion",1,"code","INTERNAL","message","服务暂时无法完成请求","traceId",trace,"retryable",false));}
}
