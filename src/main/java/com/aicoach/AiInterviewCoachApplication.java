package com.aicoach;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 面试模拟平台启动类
 *
 * @author aicoach
 */
@SpringBootApplication
@MapperScan("com.aicoach.mapper")
public class AiInterviewCoachApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiInterviewCoachApplication.class, args);
        System.out.println("\n========================================");
        System.out.println("  AI Interview Coach 启动成功");
        System.out.println("  访问: http://localhost:8080/api/health");
        System.out.println("========================================\n");
    }
}