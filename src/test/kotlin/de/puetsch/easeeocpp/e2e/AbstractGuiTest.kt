package de.puetsch.easeeocpp.e2e

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions
import de.puetsch.easeeocpp.web.StateFixtures
import io.qameta.allure.Allure
import io.qameta.allure.Epic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInfo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val ARTIFACT_ROOT = "build/playwright"
private const val SCREENSHOT_FILE = "seite.png"
private const val TRACE_FILE = "trace.zip"
private const val ASSERTION_TIMEOUT_MS = 10_000.0
private const val SECTION_VERBINDUNGEN = "section-verbindungen"

@Tag("e2e")
@Epic("GUI")
@SpringBootTest(
    classes = [GuiTestApplication::class, GuiTestConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
abstract class AbstractGuiTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    protected lateinit var stateStore: FakeControlStateStore

    protected lateinit var page: Page

    private lateinit var context: BrowserContext
    private lateinit var artifactDirectory: Path

    @BeforeEach
    fun startBrowserContext(testInfo: TestInfo) {
        stateStore.state = StateFixtures.fullState(Instant.now())
        artifactDirectory = artifactDirectoryFor(testInfo)
        context = browser.newContext()
        context.tracing().start(Tracing.StartOptions().setScreenshots(true).setSnapshots(true))
        page = context.newPage()
    }

    @AfterEach
    fun finishBrowserContext() {
        val screenshot = artifactDirectory.resolve(SCREENSHOT_FILE)
        page.screenshot(Page.ScreenshotOptions().setPath(screenshot).setFullPage(true))
        context.tracing().stop(Tracing.StopOptions().setPath(artifactDirectory.resolve(TRACE_FILE)))
        context.close()
        attachFile("Screenshot", "image/png", screenshot, "png")
        attachFile("Playwright-Trace", "application/zip", artifactDirectory.resolve(TRACE_FILE), "zip")
    }

    protected fun openPage(): Page {
        schritt("Seite öffnen") {
            page.navigate(baseUrl())
            PlaywrightAssertions.assertThat(page.locator("[data-testid='$SECTION_VERBINDUNGEN']")).isVisible()
        }
        return page
    }

    protected fun schritt(name: String, action: () -> Unit) {
        Allure.step(name, Allure.ThrowableRunnableVoid { action() })
    }

    protected fun baseUrl(): String = "http://localhost:$port/"

    protected fun chargerRow(chargerId: String): Locator = page.locator("[data-testid='charger-$chargerId']")

    protected fun valueOf(testId: String): Locator = page.locator("[data-testid='$testId'] .wert")

    protected fun textOf(testId: String): Locator = page.locator("[data-testid='$testId']")

    protected fun assertShownText(testId: String, expectedText: String) {
        PlaywrightAssertions.assertThat(textOf(testId)).hasText(expectedText, hasTextOptions())
    }

    protected fun assertShownValue(testId: String, expectedValue: String) {
        PlaywrightAssertions.assertThat(valueOf(testId)).hasText(expectedValue, hasTextOptions())
    }

    private fun hasTextOptions(): LocatorAssertions.HasTextOptions =
        LocatorAssertions.HasTextOptions().setTimeout(ASSERTION_TIMEOUT_MS)

    private fun attachFile(name: String, mimeType: String, file: Path, extension: String) {
        Files.newInputStream(file).use { Allure.addAttachment(name, mimeType, it, extension) }
    }

    private fun artifactDirectoryFor(testInfo: TestInfo): Path {
        val methodName = testInfo.testMethod.map { it.name }.orElse("unbekannt")
        return Path.of(ARTIFACT_ROOT, methodName.replace(Regex("[^A-Za-z0-9_-]"), "_")).also { Files.createDirectories(it) }
    }

    companion object {
        private lateinit var playwright: Playwright
        private lateinit var browser: Browser

        @JvmStatic
        @BeforeAll
        fun launchBrowser() {
            playwright = Playwright.create()
            browser = ChromiumProvisioner(playwright).launchHeadless()
            PlaywrightAssertions.setDefaultAssertionTimeout(ASSERTION_TIMEOUT_MS)
        }

        @JvmStatic
        @AfterAll
        fun closeBrowser() {
            browser.close()
            playwright.close()
        }
    }
}
