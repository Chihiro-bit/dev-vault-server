package com.devvault.server.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DevVaultApplication

fun main(args: Array<String>) {
    runApplication<DevVaultApplication>(*args)
}
