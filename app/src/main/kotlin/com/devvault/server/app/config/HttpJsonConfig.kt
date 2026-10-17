package com.devvault.server.app.config

import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter

/**
 * 接口模型都标注了 @Serializable，Spring 会自动注册一个使用 kotlinx 默认配置的
 * KotlinSerializationJsonHttpMessageConverter，而它的默认行为是「省略等于默认值的字段」
 * 和「省略 null」，会导致响应缺少 `kdf.type`、`user.email`、`payload` 等字段，
 * 与 docs/server-api.md 中的示例不一致。
 *
 * 这里用同名 Bean 替换它（HttpMessageConverters 会用同类型的自定义转换器替换默认实现），
 * 参数与原 Ktor 版本的 Json 配置保持一致并显式输出 null，保证契约稳定。
 */
@Configuration
class HttpJsonConfig {

    @Bean
    fun kotlinSerializationJsonHttpMessageConverter(): KotlinSerializationJsonHttpMessageConverter =
        KotlinSerializationJsonHttpMessageConverter(json())

    private fun json(): Json = Json {
        // 输出等于默认值的字段（如 kdf.type / deleted），不要省略
        encodeDefaults = true
        // 显式输出 null（如 user.email / 墓碑记录的 payload），与接口文档示例一致
        explicitNulls = true
        // 容忍客户端多传字段，便于后续版本平滑升级
        ignoreUnknownKeys = true
    }
}
