package mindustrymoddevelopmentplugin.dsl

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies Termux recognition.
 *
 * Nothing here reads the real environment: the os name, `PREFIX` and `TERMUX_VERSION` are all passed in,
 * which is also what lets the Android branch of the data directory be tested at all.
 */
class HostPlatformTest {

    @Test
    fun `termux is detected from TERMUX_VERSION`() {
        assertTrue(
            HostPlatform.detect("Linux", null, "0.118.3") == HostPlatform.Android
        )
    }

    @Test
    fun `termux is detected from a PREFIX inside com_termux`() {
        assertTrue(
            HostPlatform.detect("Linux", "/data/data/com.termux/files/usr", null) ==
                HostPlatform.Android
        )
    }

    @Test
    fun `a PREFIX that is not Termux is not enough`() {
        // Plenty of unrelated tools export PREFIX, so it only counts when it points inside com.termux.
        assertTrue(HostPlatform.detect("Linux", "/usr/local", null) == HostPlatform.Desktop)
        assertTrue(HostPlatform.detect("Linux", null, null) == HostPlatform.Desktop)
    }

    @Test
    fun `other operating systems are desktops even with a termux variable set`() {
        assertTrue(HostPlatform.detect("Mac OS X", null, "0.118.3") == HostPlatform.Desktop)
        assertTrue(HostPlatform.detect("Windows 11", null, "0.118.3") == HostPlatform.Desktop)
    }

    @Test
    fun `x86_64 termux is still android, because the desktop jar cannot run there either`() {
        assertTrue(
            HostPlatform.detect("Linux", "/data/data/com.termux/files/usr", "0.118.3") ==
                HostPlatform.Android
        )
    }
}
