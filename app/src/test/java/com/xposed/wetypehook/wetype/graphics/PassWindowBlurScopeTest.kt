package com.xposed.wetypehook.wetype.graphics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassWindowBlurScopeTest {
    private val scope = PassWindowBlurScope()
    private val host = "com.tencent.wetype"

    @Test
    fun admitsOnlyTheExactPackageDuringTheCarrierCall() {
        assertFalse(scope.allows(null))
        assertFalse(scope.allows(host))
        scope.withPackage(host) {
            assertTrue(scope.allows(host))
            assertFalse(scope.allows("$host.other"))
            assertFalse(scope.allows("com.xposed.wetypehook"))
        }
        assertFalse(scope.allows(host))
    }

    @Test
    fun restoresAdmissionWhenTheNativeCallFails() {
        runCatching {
            scope.withPackage(host) {
                assertTrue(scope.allows(host))
                error("Native call failed")
            }
        }
        assertFalse(scope.allows(host))
    }

    @Test
    fun nestedCallsRestoreTheOuterPackage() {
        scope.withPackage(host) {
            scope.withPackage("other") {
                assertTrue(scope.allows("other"))
                assertFalse(scope.allows(host))
            }
            assertTrue(scope.allows(host))
        }
    }
}
