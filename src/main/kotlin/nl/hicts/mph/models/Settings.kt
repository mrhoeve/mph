package nl.hicts.mph.models

import java.nio.file.Path

data class Settings(
    val basePath: Path?,
    val maxScanDepth: Int,
    val nexusIqUrl: String? = null,
    val nexusIqUser: String? = null,
    val nexusIqPass: String? = null,
    val nexusIqAppIdPrefix: String? = null,
    val nexusIqAppIdSuffix: String? = null
)
