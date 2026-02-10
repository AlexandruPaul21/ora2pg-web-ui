package com.alexandrupaul.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Ora2pgWebBackendApplication

fun main(args: Array<String>) {
    runApplication<Ora2pgWebBackendApplication>(*args)
}
