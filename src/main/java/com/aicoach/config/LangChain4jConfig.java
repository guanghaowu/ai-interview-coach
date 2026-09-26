package com.aicoach.config;

import com.aicoach.ai.InterviewAiService;
import com.aicoach.ai.InterviewTools;
import com.aicoach.mapper.QuestionMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 配置
 *
 * DeepSeek 提供 OpenAI 兼容协议，所以直接用 OpenAiChatModel 指向 DeepSeek 的 base-url。
 * 这里手动配置，不依赖 spring-boot-starter 的自动配置（避免多 Bean 冲突）。
 */
@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.temperature:0.7}")
    private Double temperature;

    @Value("${langchain4j.open-ai.chat-model.max-tokens:2000}")
    private Integer maxTokens;

    /**
     * 聊天模型（指向 DeepSeek）
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Function Calling 工具（供模型调用以获取真实数据）
     */
    @Bean
    public InterviewTools interviewTools(QuestionMapper questionMapper) {
        return new InterviewTools(questionMapper);
    }

    /**
     * AI 服务代理（LangChain4j 自动生成 InterviewAiService 的实现）
     * 注册 tools 后即开启 Function Calling 能力
     */
    @Bean
    public InterviewAiService interviewAiService(ChatLanguageModel chatLanguageModel,
                                                 InterviewTools interviewTools) {
        return AiServices.builder(InterviewAiService.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(interviewTools)
                .build();
    }
}