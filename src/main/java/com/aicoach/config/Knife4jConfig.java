package com.aicoach.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（Knife4j / OpenAPI3）
 *
 * 启动后访问 http://localhost:8080/doc.html
 * 点右上角「Authorize」，填入登录接口返回的 token（不用带 "Bearer " 前缀），
 * 之后调试需要鉴权的接口会自动带上 Authorization 头。
 */
@Configuration
public class Knife4jConfig {

    /** 与 JwtInterceptor 约定的认证头名称保持一致 */
    private static final String SECURITY_SCHEME_NAME = "Bearer";

    @Bean
    public OpenAPI aiInterviewCoachOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AiInterviewCoach 接口文档")
                        .description("AI 面试模拟平台：输入 JD → AI 出题 → 作答 → AI 评分并给出改进建议")
                        .version("0.0.1-SNAPSHOT")
                        .contact(new Contact().name("AiInterviewCoach")))
                // 全局声明 JWT 认证方案，省得每个接口单独标注
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
