package studio;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String origin;
    public WebConfig(@Value("${studio.cors-origin}") String origin) { this.origin = origin; }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origin).allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Demo-User").maxAge(3600);
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getMethod().equals("OPTIONS")) return true;
                String user = request.getHeader("X-Demo-User");
                if (!"alice".equals(user) && !"bob".equals(user)) throw ApiError.bad("X-Demo-User 必须是 alice 或 bob");
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
