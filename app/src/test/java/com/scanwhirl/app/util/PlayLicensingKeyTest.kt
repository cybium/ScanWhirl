package com.scanwhirl.app.util

import com.scanwhirl.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class PlayLicensingKeyTest {
    @Test
    fun `BuildConfig carries a valid RSA 2048 Play public key`() {
        val encoded = Base64.getDecoder().decode(BuildConfig.PLAY_LICENSE_KEY_B64)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded)) as RSAPublicKey
        assertEquals(2048, key.modulus.bitLength())
    }
}
