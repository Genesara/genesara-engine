package dev.gvart.genesara

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GenesaraApplication

fun main(args: Array<String>) {
    runApplication<GenesaraApplication>(*args)
}