package nl.hicts.mph.configuration

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.reactive.config.CorsRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
@Profile("ide")
class IdeCorsConfiguration : WebFluxConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins(
                "http://localhost:4200",
                "http://127.0.0.1:4200"
            )
            .allowedMethods("*")
            .allowedHeaders("*")
            .allowCredentials(false)
            .maxAge(3600)
    }
}
