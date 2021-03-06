@file:JvmName("JavaNioBitsConfiguration")
package net.corda.djvm.analysis

import net.corda.djvm.code.CLASS_CONSTRUCTOR_NAME
import net.corda.djvm.code.DJVM_NAME
import net.corda.djvm.code.EmitterModule
import net.corda.djvm.references.Member
import org.objectweb.asm.Opcodes.*

/**
 * Generate [Member] objects that will be stitched into [sandbox.java.nio.Bits].
 */
fun generateJavaBitsMethods(): List<Member> = listOf(
    object : MethodBuilder(
        access = ACC_STATIC,
        className = "sandbox/java/nio/Bits",
        memberName = CLASS_CONSTRUCTOR_NAME,
        descriptor = "()V"
    ) {
        /**
         * Replace <clinit>():
         *     byteOrder = DJVM.getNativeOrder()
         */
        override fun writeBody(emitter: EmitterModule) = with(emitter) {
            invokeStatic(DJVM_NAME, "getNativeOrder", "()Lsandbox/java/nio/ByteOrder;")
            putStatic(className, "byteOrder", "Lsandbox/java/nio/ByteOrder;")
            returnVoid()
        }
    }.withBody().build()
)
