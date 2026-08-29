package com.example.chat.config;

import com.example.chat.mymodel.MyModelChatModel;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Routes a request's {@code modelName} to one of the configured {@link ChatModel}
 * beans by prefix. {@code claude*} -> Anthropic; {@code deepseek*} -> DeepSeek;
 * everything else (e.g. {@code gpt-*}, {@code azure-gpt-*}) -> OpenAI.
 */
@Component
public class ModelRouter {

    private final AnthropicChatModel anthropicChatModel;
    private final OpenAiChatModel openAiChatModel;
    private final DeepSeekChatModel deepSeekChatModel;
    private final MyModelChatModel myModelChatModel;

    public ModelRouter(ObjectProvider<AnthropicChatModel> anthropic,
                       ObjectProvider<OpenAiChatModel> openai,
                       ObjectProvider<DeepSeekChatModel> deepseek,
                       ObjectProvider<MyModelChatModel> myModel) {
        this.anthropicChatModel = anthropic.getIfAvailable();
        this.openAiChatModel = openai.getIfAvailable();
        this.deepSeekChatModel = deepseek.getIfAvailable();
        this.myModelChatModel = myModel.getIfAvailable();
    }

    public ChatModel resolve(String modelName) {
        Provider provider = providerOf(modelName);
        return switch (provider) {
            case ANTHROPIC -> requireModel(anthropicChatModel, "Anthropic");
            case DEEPSEEK  -> requireModel(deepSeekChatModel, "DeepSeek");
            case MY_MODEL  -> requireModel(myModelChatModel,
                    "MyModel （请在 application.yaml 中设置 my-model.enabled=true）");
            case OPENAI    -> requireModel(openAiChatModel, "OpenAI");
        };
    }

    /**
     * 返回一个全新的 {@link ChatClient.Builder}，绑定 {@link #resolve(String)} 路由出的
     * {@link ChatModel}。每次调用都构造新 builder，调用方可以放心 {@code clone()} 后做
     * per-request 定制（如子代理场景下追加 advisors / tools / toolContext），不会污染主 agent。
     *
     * <p>主要用于子代理执行器（{@code SandboxSubagentExecutor}）按请求 modelName 构造独立的
     * ChatClient —— 子代理与主 agent 共用底层 ChatModel（共享连接池/限流配置），但 advisor 链、
     * 工具集、system prompt 互不影响。
     */
    public ChatClient.Builder chatClientBuilder(String modelName) {
        return ChatClient.builder(resolve(modelName));
    }

    public Provider providerOf(String modelName) {
        if (modelName != null) {
            String lower = modelName.toLowerCase();
            if (lower.startsWith("claude")) {
                return Provider.ANTHROPIC;
            }
            // DeepSeek 官方模型前缀走 DeepSeek provider（v4 系列输出 reasoning_content）；
            // 注意：必须在下面的 lower.startsWith("deepseek")（MY_MODEL 兜底）之前匹配，
            // 否则 v4 系列会被错配到 MY_MODEL。
            if (lower.startsWith("deepseek-chat")
                    || lower.startsWith("deepseek-reasoner")
                    || lower.startsWith("deepseek-v4-")) {
                return Provider.DEEPSEEK;
            }
            // 自定义模型的路由前缀，包括示例中的 qwen2.5-vl-7b / openai-compatible
            if (lower.startsWith("qwen")
                    || lower.startsWith("glm")
                    || lower.startsWith("openai-compatible")
                    || lower.startsWith("doubao")
                    || lower.startsWith("deepseek")
                    || lower.startsWith("my-")) {
                return Provider.MY_MODEL;
            }
        }
        return Provider.OPENAI;
    }

    private static <T extends ChatModel> T requireModel(T model, String label) {
        if (model == null) {
            throw new IllegalStateException(label + " ChatModel is not configured. "
                    + "Set the corresponding spring.ai.<provider>.api-key property.");
        }
        return model;
    }

    public enum Provider { ANTHROPIC, DEEPSEEK, MY_MODEL, OPENAI }
}
