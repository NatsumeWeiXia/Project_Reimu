package io.reimu.server;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller public class Pages {
    // Only declared frontend paths fall back. API and missing asset 404s remain real errors.
    @GetMapping({"/","/app","/app/chat/new","/app/chat/{id}","/app/admin/{page}"}) public String index(){return "forward:/index.html";}
}
