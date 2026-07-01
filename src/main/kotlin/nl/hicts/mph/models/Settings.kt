package nl.hicts.mph.models

import java.nio.file.Path

data class Settings(
    val basePath: Path?,
    val maxScanDepth: Int
)
