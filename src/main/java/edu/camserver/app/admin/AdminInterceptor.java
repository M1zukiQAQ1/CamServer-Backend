package edu.camserver.app.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Gate for every {@code /api/admin/**} request, however it arrived (nginx also maps
 * {@code /api/backend/api/admin/**} here, so this check, not nginx, is authoritative):
 * <ol>
 *   <li>the client must be on an allowed network (UCSB VPN), else 403;</li>
 *   <li>writes must be JSON, which a cross-site form cannot send;</li>
 *   <li>everything except {@code login} and {@code session} needs a valid session cookie, else 401.</li>
 * </ol>
 */
@Configuration
public class AdminInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    static final String SESSION_ATTRIBUTE = AdminInterceptor.class.getName() + ".session";
    static final String CLIENT_ATTRIBUTE = AdminInterceptor.class.getName() + ".client";
    private static final Set<String> OPEN = Set.of("/api/admin/login", "/api/admin/session");

    private final AdminAuthService auth;
    private final ObjectMapper mapper;

    public AdminInterceptor(AdminAuthService auth, ObjectMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/admin", "/api/admin/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equals(request.getMethod())) return true;
        AdminAuthService.Client client = auth.client(request);
        request.setAttribute(CLIENT_ATTRIBUTE, client);
        response.setHeader("Cache-Control", "no-store");
        if (!client.allowed()) {
            return deny(response, HttpServletResponse.SC_FORBIDDEN,
                    "The admin panel is only available over the UCSB VPN. Your address: " + client.address());
        }
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            String type = request.getContentType();
            if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(type))) {
                return deny(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Send application/json.");
            }
        }
        Optional<AdminAuthService.Session> session = auth.session(token(request));
        session.ifPresent(s -> request.setAttribute(SESSION_ATTRIBUTE, s));
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (session.isEmpty() && !OPEN.contains(path)) {
            return deny(response, HttpServletResponse.SC_UNAUTHORIZED, "Log in first.");
        }
        return true;
    }

    static String token(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (AdminAuthService.COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private boolean deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("ok", false, "error", message));
        return false;
    }
}
