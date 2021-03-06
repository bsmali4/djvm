package com.example.testing

import com.example.testing.SandboxType.KOTLIN
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SignatureScheme
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.hash
import net.corda.core.serialization.internal._contextSerializationEnv
import net.corda.core.serialization.serialize
import net.corda.serialization.djvm.createSandboxSerializationEnv
import net.corda.serialization.djvm.deserializeFor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.provider.CsvSource
import java.security.Signature
import java.util.stream.Stream

@ExtendWith(LocalSerialization::class)
class CryptoTest : TestBase(KOTLIN) {
    companion object {
        const val IMPORTANT_MESSAGE = "Very Important Message! Trust Me!"
        const val PASSWORD = "deterministic"

        @RegisterExtension
        @JvmField
        val keyStore = KeyStoreProvider("keystore.pkcs12", PASSWORD, "PKCS12")
    }

    class SignatureSchemeProvider : ArgumentsProvider {
        override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> {
            return Crypto.supportedSignatureSchemes().stream()
                .filter { it != Crypto.COMPOSITE_KEY }
                .map { Arguments.of(it) }
        }
    }

    @ArgumentsSource(SignatureSchemeProvider::class)
    @ParameterizedTest(name = "deserialise public key: {index} => {0}")
    fun `test non-composite public keys`(signatureScheme: SignatureScheme) {
        val keyPair = Crypto.generateKeyPair(signatureScheme)
        val key = keyPair.public
        val data = key.serialize()

        sandbox {
            _contextSerializationEnv.set(createSandboxSerializationEnv(classLoader))
            val sandboxKey = data.deserializeFor(classLoader)

            val taskFactory = classLoader.createRawTaskFactory()
            val publicKeyFunction = taskFactory.compose(classLoader.createSandboxFunction()).apply(PublicKeyFunction::class.java)
            val result = publicKeyFunction.apply(sandboxKey)
            assertThat(result.toString())
                .isEqualTo("Format='${key.format}', Algorithm='${key.algorithm}', Hash='${key.hash}'")
        }
    }

    @Test
    fun `test composite public key`() {
        val key1 = Crypto.generateKeyPair(Crypto.ECDSA_SECP256K1_SHA256).public
        val key2 = Crypto.generateKeyPair(Crypto.ECDSA_SECP256R1_SHA256).public
        val key3 = Crypto.generateKeyPair(Crypto.EDDSA_ED25519_SHA512).public

        val compositeKey = CompositeKey.Builder()
            .addKey(key1, weight = 1)
            .addKey(key2, weight = 1)
            .addKey(key3, weight = 1)
            .build(2)
        val data = compositeKey.serialize()

        sandbox {
            _contextSerializationEnv.set(createSandboxSerializationEnv(classLoader))
            val sandboxKey = data.deserializeFor(classLoader)

            val taskFactory = classLoader.createRawTaskFactory()
            val publicKeyFunction = taskFactory.compose(classLoader.createSandboxFunction()).apply(PublicKeyFunction::class.java)
            val result = publicKeyFunction.apply(sandboxKey)
            assertThat(result.toString())
                .isEqualTo("Format='${compositeKey.format}', Algorithm='${compositeKey.algorithm}', Hash='${compositeKey.hash}'")
       }
    }

    @Test
    fun `test marshalling a Corda party`() {
        val owningKey = Crypto.generateKeyPair(Crypto.ECDSA_SECP256K1_SHA256).public
        val party = Party(CordaX500Name("Alice Corp", "Madrid", "ES"), owningKey)
        val data = party.serialize()

        sandbox {
            _contextSerializationEnv.set(createSandboxSerializationEnv(classLoader))
            val sandboxParty = data.deserializeFor(classLoader)

            assertEquals("sandbox.net.corda.core.identity.Party", sandboxParty::class.java.name)
            assertEquals("O=Alice Corp, L=Madrid, C=ES", sandboxParty.toString())
        }
    }

    @ArgumentsSource(SignatureSchemeProvider::class)
    @ParameterizedTest(name = "verifying signature: {index} => {0}")
    fun `test verifying signature`(signatureScheme: SignatureScheme) {
        val clearData = IMPORTANT_MESSAGE.toByteArray()

        val keyPair = Crypto.generateKeyPair(signatureScheme)
        val signature = Crypto.doSign(signatureScheme, keyPair.`private`, clearData)

        val nameData = signatureScheme.schemeCodeName.serialize()
        val keyData = keyPair.`public`.serialize()

        sandbox {
            _contextSerializationEnv.set(createSandboxSerializationEnv(classLoader))
            val sandboxSchemeName = nameData.deserializeFor(classLoader)
            val sandboxKey = keyData.deserializeFor(classLoader)

            val taskFactory = classLoader.createRawTaskFactory()
            val verifier = taskFactory.compose(classLoader.createSandboxFunction()).apply(VerifySignature::class.java)
            val result = verifier.apply(arrayOf(sandboxSchemeName, sandboxKey, signature, clearData))
            assertEquals(true.toString(), result.toString())
        }
    }

    @CsvSource("rsa,SHA256WithRSA", "ec,SHA256WithECDSA")
    @ParameterizedTest(name = "verifying with certificate: {index} => {0},{1}")
    fun `test verify signature with certificate`(alias: String, algorithm: String) {
        val clearData = IMPORTANT_MESSAGE.toByteArray()

        val keyPair = keyStore.getKeyPair(alias, PASSWORD)
        val certificate = keyStore.getCertificate(alias)

        val algorithmData = algorithm.serialize()
        val certificateData = certificate.serialize()
        val signature = with(Signature.getInstance(algorithm)) {
            initSign(keyPair.`private`)
            update(clearData)
            sign()
        }

        sandbox {
            _contextSerializationEnv.set(createSandboxSerializationEnv(classLoader))
            val sandboxCertificate = certificateData.deserializeFor(classLoader)
            val sandboxAlgorithm = algorithmData.deserializeFor(classLoader)

            val taskFactory = classLoader.createRawTaskFactory()
            val verifier = taskFactory.compose(classLoader.createSandboxFunction()).apply(VerifyWithCertificate::class.java)
            val result = verifier.apply(arrayOf(sandboxAlgorithm, sandboxCertificate, signature, clearData))
            assertEquals(true.toString(), result.toString())
        }
    }
}
