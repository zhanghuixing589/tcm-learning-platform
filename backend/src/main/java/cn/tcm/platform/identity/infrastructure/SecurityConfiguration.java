package cn.tcm.platform.identity.infrastructure;

import cn.tcm.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain security(HttpSecurity http,SecurityProperties properties,IdentityMapper mapper,
                                       TokenService tokens,ObjectMapper json) throws Exception {
        var cors=new CorsConfiguration();cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET","POST","PATCH","PUT","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization","Content-Type","X-CSRF-Token"));
        cors.setExposedHeaders(List.of("X-Trace-Id"));cors.setAllowCredentials(true);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",cors);
        http.cors(c->c.configurationSource(source)).csrf(c->c.disable())
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
                .exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->error(json,res,401,"UNAUTHENTICATED","请先登录"))
                        .accessDeniedHandler((req,res,e)->error(json,res,403,"FORBIDDEN","没有执行此操作的权限")))
                .authorizeHttpRequests(c->c.requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness",
                        "/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/logout","/api/v1/capabilities").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                        boolean cookieEndpoint=req.getRequestURI().equals("/api/v1/auth/refresh") || req.getRequestURI().equals("/api/v1/auth/logout");
                        String origin=req.getHeader("Origin");
                        if(!"OPTIONS".equals(req.getMethod()) && (cookieEndpoint && (!"POST".equals(req.getMethod()) || !"1".equals(req.getHeader("X-CSRF-Token")) || !properties.allowedOrigins().contains(origin))
                                || origin!=null && !properties.allowedOrigins().contains(origin))) {
                            error(json,res,403,"FORBIDDEN","请求来源校验失败");return;
                        }
                        String header=req.getHeader("Authorization");
                        if(header!=null) {
                            try {
                                if(!header.startsWith("Bearer "))throw new IllegalArgumentException();
                                var jwt=tokens.decode(header.substring(7));long id=Long.parseLong(jwt.getSubject());
                                var user=mapper.selectById(id);
                                Object version=jwt.getClaim("cv");
                                if(user==null || !"ACTIVE".equals(user.status) || !(version instanceof Number)
                                        || user.credentialVersion.intValue()!=((Number)version).intValue()
                                        || mapper.activeFamily(id,jwt.getClaimAsString("sid"))==0)throw new IllegalArgumentException();
                                var authorities=new ArrayList<SimpleGrantedAuthority>();
                                mapper.roles(id).forEach(r->authorities.add(new SimpleGrantedAuthority("ROLE_"+r)));
                                mapper.permissions(id).forEach(p->authorities.add(new SimpleGrantedAuthority(p)));
                                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(id),null,authorities));
                            } catch(org.springframework.security.oauth2.jwt.JwtException | IllegalArgumentException e) {
                                SecurityContextHolder.clearContext();error(json,res,401,"UNAUTHENTICATED","登录已失效，请重新登录");return;
                            }
                        }
                        chain.doFilter(req,res);
                    }
                },UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    private static void error(ObjectMapper json,HttpServletResponse res,int status,String code,String message) throws IOException {
        res.setStatus(status);res.setContentType("application/json;charset=UTF-8");
        json.writeValue(res.getWriter(),ApiResponse.error(code,message));
    }
}
