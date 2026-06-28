package nl.hicts.mph

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MphApplication

fun main(args: Array<String>) {
	runApplication<MphApplication>(*args)
}
