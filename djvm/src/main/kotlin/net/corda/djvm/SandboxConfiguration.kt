package net.corda.djvm

import net.corda.djvm.analysis.AnalysisConfiguration
import net.corda.djvm.analysis.AnalysisConfiguration.ChildBuilder
import net.corda.djvm.code.*
import net.corda.djvm.execution.ExecutionProfile
import net.corda.djvm.execution.IsolatedTask
import net.corda.djvm.rewiring.ByteCodeCache
import net.corda.djvm.rules.Rule
import net.corda.djvm.rules.implementation.*
import net.corda.djvm.rules.implementation.instrumentation.*
import net.corda.djvm.source.UserSource
import net.corda.djvm.utilities.loggerFor
import java.io.IOException
import java.net.URL
import java.util.Collections.unmodifiableList
import java.util.function.Consumer
import java.util.zip.ZipInputStream

/**
 * Configuration to use for the deterministic sandbox. It also caches the bytecode
 * for the sandbox classes that have been generated according to these rules.
 *
 * @property rules The rules to apply during the analysis phase.
 * @property emitters The code emitters / re-writers to apply to all loaded classes.
 * @property definitionProviders The meta-data providers to apply to class and member definitions.
 * @property executionProfile The execution profile to use in the sandbox.
 * @property analysisConfiguration The configuration used in the analysis of classes.
 * @property byteCodeCache A cache of bytecode generated using these rules, emitters and definition providers.
 */
class SandboxConfiguration private constructor(
    val rules: List<Rule>,
    val emitters: List<Emitter>,
    val definitionProviders: List<DefinitionProvider>,
    val executionProfile: ExecutionProfile,
    val analysisConfiguration: AnalysisConfiguration,
    val byteCodeCache: ByteCodeCache
) {
    /**
     * Creates a child [SandboxConfiguration] with this instance as its parent.
     * @param userSource Source for additional classes to be included in the new sandbox.
     * @param configure A callback function so that we can configure the [ChildBuilder].
     */
    fun createChild(userSource: UserSource, configure: Consumer<ChildBuilder>): SandboxConfiguration {
        return SandboxConfiguration(
            rules = rules,
            emitters = emitters,
            definitionProviders = definitionProviders,
            executionProfile = executionProfile,
            analysisConfiguration = with(analysisConfiguration.createChild(userSource)) {
                configure.accept(this)
                build()
            },
            byteCodeCache = ByteCodeCache(byteCodeCache)
        )
    }

    /**
     * Creates a child [SandboxConfiguration] with this instance as its parent.
     * @param userSource Source for additional classes to be included in the new sandbox.
     */
    fun createChild(userSource: UserSource): SandboxConfiguration {
        return createChild(userSource, Consumer {})
    }

    /**
     * Generate sandbox byte-code for every class inside selected source JARs.
     * These source jars must each contain a META-INF/DJVM-preload entry.
     */
    @Throws(ClassNotFoundException::class, IOException::class)
    fun preload() {
        val preloadURLs = getPreloadURLs()
        if (preloadURLs.isNotEmpty()) {
            IsolatedTask(PRELOAD_THREAD_PREFIX, this).run {
                val knownReferences = HashSet<String>(INITIAL_CLASSES)

                /**
                 * Generate sandbox byte-code for all of these jars.
                 */
                for (preloadURL in preloadURLs) {
                    log.info("Preloading classes from {}", preloadURL.path)
                    ZipInputStream(preloadURL.openStream()).use {
                        while (true) {
                            val entryName = (it.nextEntry ?: break).name
                            if (entryName.endsWith(CLASS_SUFFIX)) {
                                val internalClassName = entryName.dropLast(CLASS_SUFFIX.length)
                                knownReferences.add(internalClassName)

                                val className = internalClassName.asPackagePath
                                classLoader.toSandboxClass(className)
                                log.debug("- loaded {}", className)

                                /**
                                 * Now ensure that we've also loaded every other
                                 * class that this class has referenced.
                                 */
                                classLoader.resolveReferences(knownReferences)
                            }
                        }
                    }
                }

                log.info("Preloaded {} classes into sandbox.",
                          knownReferences.size - INITIAL_CLASSES.size)
            }
        }
    }

    private fun getPreloadURLs(): Set<URL> {
        return with (analysisConfiguration.supportingClassLoader) {
            val sourceURLs = getAllURLs()
            getResources(DJVM_PRELOAD_TAG).asSequence().mapNotNullTo(LinkedHashSet()) { url ->
                val jarPath = url.path.substringBeforeLast("!/")
                sourceURLs.find { it.toString().endsWith(jarPath) }
            }
        }
    }

    companion object {
        const val DJVM_PRELOAD_TAG = "META-INF/DJVM-preload"
        private const val PRELOAD_THREAD_PREFIX = "preloader"
        private const val CLASS_SUFFIX = ".class"
        private val INITIAL_CLASSES = setOf(
            // These classes are always present inside a sandbox.
            OBJECT_NAME, "java/lang/StackTraceElement", THROWABLE_NAME
        )

        private val log = loggerFor<SandboxConfiguration>()

        @JvmField
        val ALL_RULES: List<Rule> = unmodifiableList(listOf(
            AlwaysUseNonSynchronizedMethods,
            AlwaysUseStrictFloatingPointArithmetic,
            DisallowOverriddenSandboxPackage,
            DisallowSandboxInstructions,
            DisallowSandboxMethods,
            DisallowUnsupportedApiVersions
        ))

        @JvmField
        val ALL_DEFINITION_PROVIDERS: List<DefinitionProvider> = unmodifiableList(listOf(
            AlwaysInheritFromSandboxedObject,
            AlwaysUseNonSynchronizedMethods,
            AlwaysUseStrictFloatingPointArithmetic,
            StaticConstantRemover,
            StubOutFinalizerMethods,
            StubOutNativeMethods,
            StubOutReflectionMethods
        ))

        @JvmField
        val ALL_EMITTERS: List<Emitter> = unmodifiableList(listOf(
            AlwaysInheritFromSandboxedObject,
            AlwaysUseExactMath,
            ArgumentUnwrapper,
            DisallowDynamicInvocation,
            DisallowCatchingBlacklistedExceptions,
            DisallowNonDeterministicMethods,
            HandleExceptionUnwrapper,
            IgnoreBreakpoints,
            IgnoreSynchronizedBlocks,
            ReturnTypeWrapper,
            RewriteClassMethods,
            RewriteObjectMethods,
            StringConstantWrapper,
            ThrowExceptionWrapper,
            TraceAllocations,
            TraceInvocations,
            TraceJumps,
            TraceThrows
        ))

        /**
         * Create a sandbox configuration where one or more properties deviates from the default.
         */
        fun of(
            profile: ExecutionProfile = ExecutionProfile.DEFAULT,
            rules: List<Rule> = ALL_RULES,
            emitters: List<Emitter>? = null,
            definitionProviders: List<DefinitionProvider> = ALL_DEFINITION_PROVIDERS,
            enableTracing: Boolean = true,
            analysisConfiguration: AnalysisConfiguration
        ) = SandboxConfiguration(
                executionProfile = profile,
                rules = rules,
                emitters = (emitters ?: ALL_EMITTERS).filter {
                    enableTracing || it.priority > EMIT_TRACING
                },
                definitionProviders = definitionProviders,
                analysisConfiguration = analysisConfiguration,
                byteCodeCache = ByteCodeCache.createFor(analysisConfiguration)
        )

        /**
         * Create a fresh [SandboxConfiguration] that contains all rules,
         * emitters and definition providers.
         */
        fun createFor(
            analysisConfiguration: AnalysisConfiguration,
            profile: ExecutionProfile,
            enableTracing: Boolean
        ): SandboxConfiguration {
            return of(
                profile = profile,
                enableTracing = enableTracing,
                analysisConfiguration = analysisConfiguration
            )
        }
    }
}
