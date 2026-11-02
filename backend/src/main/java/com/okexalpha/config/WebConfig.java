package com.okexalpha.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 前端资源缓存策略（Vue 迁移后新增，唯一一处后端改动）。
 *
 * <ul>
 *   <li>/assets/** ：Vite 产物，文件名带内容 hash，可长期强缓存（365 天 immutable）</li>
 *   <li>/index.html ：SPA 入口，不能缓存，否则发版后浏览器拿到旧壳</li>
 *   <li>/legacy/** ：迁移期旧版界面，文件无 hash，用 ?v= 手工版本号，不缓存</li>
 *   <li>/legacy → /legacy/index.html ：静态资源处理器不做欢迎页跳转，这里补一个重定向</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final CacheControl IMMUTABLE_YEAR =
            CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private static final CacheControl NO_STORE = CacheControl.noStore();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(IMMUTABLE_YEAR);
        registry.addResourceHandler("/legacy/**")
                .addResourceLocations("classpath:/static/legacy/")
                .setCacheControl(NO_STORE);
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(NO_STORE);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/legacy", "/legacy/index.html")
                .setStatusCode(HttpStatus.MOVED_PERMANENTLY);
    }
}