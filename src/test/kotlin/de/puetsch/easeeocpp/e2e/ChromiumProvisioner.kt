package de.puetsch.easeeocpp.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import java.nio.file.Path

private const val PLAYWRIGHT_CLI_MAIN_CLASS = "com.microsoft.playwright.CLI"
private const val CHROMIUM_NAME = "chromium"

/**
 * Startet Chromium; fehlt der Browser, wird er einmalig per Playwright-CLI nachinstalliert.
 * Der Gradle-Test schlägt damit nicht an einer fehlenden Browser-Installation fehl.
 */
class ChromiumProvisioner(private val playwright: Playwright) {

    fun launchHeadless(): Browser =
        try {
            launch()
        } catch (missingBrowser: PlaywrightException) {
            installChromium()
            launch()
        }

    private fun launch(): Browser =
        playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))

    private fun installChromium() {
        val process = ProcessBuilder(
            javaBinary().toString(),
            "-cp",
            System.getProperty("java.class.path"),
            PLAYWRIGHT_CLI_MAIN_CLASS,
            "install",
            CHROMIUM_NAME,
        ).inheritIO().start()
        check(process.waitFor() == 0) { "Playwright-Installation von $CHROMIUM_NAME ist fehlgeschlagen" }
    }

    private fun javaBinary(): Path = Path.of(System.getProperty("java.home"), "bin", "java")
}
