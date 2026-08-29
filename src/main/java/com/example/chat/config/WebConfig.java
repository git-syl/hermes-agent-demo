package com.example.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.bind.annotation.RestController;

/**
 * 静态 SPA（前端打包产物）托管 + 全局跨域 + API 统一前缀。
 *
 * <p><b>API 前缀</b>：所有 {@code @RestController} 经 {@code addPathPrefix} 统一加上
 * {@value #API_PREFIX}（/ai-api/...）。它只作用于 {@code @RestController} 的 handler，
 * 不影响静态资源的 {@code ResourceHttpRequestHandler} —— 因此 API 与 SPA 在同一端口天然隔离，
 * 回退判断只需一个前缀比较，不会出现 {@code /chatgpt} 撞 {@code /chat} 这类边界。
 *
 * <p><b>静态资源</b>：打包产物位于 {@code classpath:/dist/}（Vite 构建输出，index.html + assets/…）。
 * <ul>
 *   <li>配置 {@code spring.web.resources.static-locations} 让 {@code /} 直接命中 {@code index.html}，
 *       同时根目录下的 {@code favicon.ico} / {@code robots.txt} / {@code sitemap.xml} 等可直接访问；</li>
 *   <li>带内容哈希的 {@code /assets/**} 与 {@code /icons/**} 用 {@code max-age=30d} 长缓存，
 *       内容变化文件名也变，不会踩到陈旧缓存；</li>
 *   <li>{@link PathResourceResolver} 开启 SPA 回退：匹配不到真实资源的非 API 请求（如 history 路由
 *       刷新 {@code /chatgpt}）回退到 {@code index.html}，避免刷新 404。</li>
 * </ul>
 *
 * <p><b>CORS</b>：开放全部 origin（含凭据），方便前端任意域名直连本服务。生产若只服务固定前端，
 * 建议把 allowedOrigins 收窄成具体域名（本项目无 Spring Security，由本配置全权负责跨域）。
 */
@Configuration
public class WebConfig {

    /** 后端 API 统一前缀；所有 {@code @RestController} 路径都挂在其下。 */
    static final String API_PREFIX = "/ai-api";

    /** 覆盖默认静态资源位置（classpath:/static、/public 等），SPA 产物放 classpath:/dist/。 */
    private static final String[] STATIC_LOCATIONS = {"classpath:/dist/"};

    @Bean
    public WebMvcConfigurer webMvcConfigurer() {
        return new WebMvcConfigurer() {

            /** 给所有 @RestController 统一加 API 前缀；静态资源处理器不是 @RestController，不受影响。 */
            @Override
            public void configurePathMatch(
                    org.springframework.web.servlet.config.annotation.PathMatchConfigurer configurer) {
                configurer.addPathPrefix(API_PREFIX, cls ->
                        AnnotationUtils.isAnnotationDeclaredLocally(RestController.class, cls));
            }

            /** 注册 SPA 静态资源：长缓存 + 回退到 index.html。 */
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/**")
                        .addResourceLocations(STATIC_LOCATIONS)
                        .setCachePeriod((int) Duration.ofDays(30).getSeconds())
                        .resourceChain(true)
                        .addResolver(new SpaPathResourceResolver());
            }

            /** 开放跨域：允许任意 origin / header / 方法，携带凭据（SSE 由 EventSource 处理）。 */
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        // 允许携带凭据时不能用 "*" 通配，改用 allowedOriginPatterns 兼容任意 origin。
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")
                        .allowedHeaders("*")
                        .exposedHeaders("Content-Type", "X-Accel-Buffering")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }

    /**
     * SPA 回退解析器：命中真实资源（js/css/图片/…）时正常返回；否则回退到 index.html，
     * 让前端 history 路由接管（nginx {@code try_files} 的等价物）。
     * 仅排除 {@value #API_PREFIX} 前缀的 API 路径 —— 这些由各自 Controller 处理，
     * 回退会遮蔽 404/405 语义。
     */
    private static final class SpaPathResourceResolver extends PathResourceResolver {

        @Override
        protected org.springframework.core.io.Resource getResource(String resourcePath,
                                                                   org.springframework.core.io.Resource location)
                throws IOException {
            String path = "/" + resourcePath;
            boolean isApi = path.equals(API_PREFIX) || path.startsWith(API_PREFIX + "/");
            if (isApi) {
                return null;   // 交给后端路由，404/405 语义保留
            }
            org.springframework.core.io.Resource resource = location.createRelative(resourcePath);
            return resource.exists() && resource.isReadable()
                    ? resource
                    : location.createRelative("index.html");
        }
    }
}
