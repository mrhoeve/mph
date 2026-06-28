package nl.hicts.mph.logging

import org.slf4j.Logger
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class LoggerDelegate<in R : Any> : ReadOnlyProperty<R, Logger> {
    override fun getValue(thisRef: R, property: KProperty<*>) =
        getLogger(getClassForLogging(thisRef.javaClass))
}
