package io.canonlog.okhttp.spring

import io.canonlog.okhttp.OkHttpCanonicalInterceptor
import okhttp3.OkHttpClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnClass(OkHttpClient::class)
@ConditionalOnProperty(
    name = ["canonlog.okhttp.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
public open class OkHttpCanonicalAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public open fun okHttpCanonicalInterceptor(): OkHttpCanonicalInterceptor =
        OkHttpCanonicalInterceptor()

    /**
     * The default customizer that adds [OkHttpCanonicalInterceptor] to a builder.
     *
     * Conditional on the bean *name*, not the type — adopters can register additional
     * `OkHttpClientBuilderCustomizer` beans (logging, auth, retries) without
     * displacing the canonical one. To replace it entirely, register a bean with
     * the same name `canonicalOkHttpClientBuilderCustomizer`.
     */
    @Bean("canonicalOkHttpClientBuilderCustomizer")
    @ConditionalOnMissingBean(name = ["canonicalOkHttpClientBuilderCustomizer"])
    public open fun canonicalOkHttpClientBuilderCustomizer(
        interceptor: OkHttpCanonicalInterceptor,
    ): OkHttpClientBuilderCustomizer = OkHttpClientBuilderCustomizer { builder ->
        builder.addInterceptor(interceptor)
    }
}
