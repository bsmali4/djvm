package net.corda.djvm

import java.lang.annotation.Inherited
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS

@Retention(RUNTIME)
@Target(CLASS)
@MustBeDocumented
@Inherited
annotation class KotlinAnnotation(
    val value: String = "<default-value>"
)
