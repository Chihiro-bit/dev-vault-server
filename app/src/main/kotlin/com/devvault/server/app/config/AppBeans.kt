package com.devvault.server.app.config

import com.devvault.server.persistence.DatabaseFactory
import com.devvault.server.persistence.DbConfig
import com.devvault.server.service.AuthService
import com.devvault.server.service.LoginThrottle
import com.devvault.server.service.SyncService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 把原来的手动装配（Application.module 里 new 出来的对象）改由 Spring 容器管理。
 * DatabaseFactory 实现 AutoCloseable，容器关闭时会自动关闭连接池。
 */
@Configuration
@EnableConfigurationProperties(AppProperties::class)
class AppBeans(private val props: AppProperties) {

    @Bean
    fun databaseFactory(): DatabaseFactory = DatabaseFactory(
        DbConfig(
            url = props.db.url,
            user = props.db.user,
            password = props.db.password,
            poolSize = props.db.poolSize,
        ),
    )

    @Bean
    fun loginThrottle(): LoginThrottle = LoginThrottle()

    @Bean
    fun authService(db: DatabaseFactory, throttle: LoginThrottle): AuthService =
        AuthService(db, props.allowRegistration, Duration.ofDays(props.sessionDays), throttle)

    @Bean
    fun syncService(db: DatabaseFactory): SyncService = SyncService(db)
}
