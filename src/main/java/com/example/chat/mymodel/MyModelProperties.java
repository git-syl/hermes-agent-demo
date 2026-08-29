package com.example.chat.mymodel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 自定义模型的配置属性，对应 {@code application.yaml} 中的 {@code my-model.*}。 */
@ConfigurationProperties(prefix = "my-model")
public class MyModelProperties {

    /** 是否启用自定义模型，默认 false。 */
    private boolean enabled = false;

    /** 模型服务地址，不含路径。 */
    private String baseUrl = "http://localhost:8078";

    /** 默认模型名。 */
    private String model = "openai-compatible";

    /** 默认温度。 */
    private Double temperature = 0.5;

    /** 默认思考模式：enabled / disabled。 */
    private String thinking = "disabled";

    /** 默认 jobType。 */
    private Integer jobType = 1;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }

    public Integer getJobType() { return jobType; }
    public void setJobType(Integer jobType) { this.jobType = jobType; }
}
