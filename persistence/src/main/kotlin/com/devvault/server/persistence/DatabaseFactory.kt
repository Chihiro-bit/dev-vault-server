package com.devvault.server.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
    val poolSize: Int = 10,
)

/** 连接池 + 启动时自动执行 Flyway 迁移 */
class DatabaseFactory(config: DbConfig) : AutoCloseable {
    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.poolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            poolName = "devvault"
        },
    )

    val database: Database

    init {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        database = Database.connect(dataSource)
    }

    /** 执行一个数据库事务（Spring MVC 为阻塞式处理，直接在请求线程上执行） */
    fun <T> tx(block: Transaction.() -> T): T =
        transaction(Connection.TRANSACTION_REPEATABLE_READ, readOnly = false, db = database) { block() }

    override fun close() = dataSource.close()
}
