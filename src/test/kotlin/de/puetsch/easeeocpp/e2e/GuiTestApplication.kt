package de.puetsch.easeeocpp.e2e

import org.springframework.boot.autoconfigure.SpringBootApplication

/** Startet nur die Web-Schicht; HA-Client, OCPP und Regelschleife bleiben außen vor. */
@SpringBootApplication(scanBasePackages = ["de.puetsch.easeeocpp.web"])
class GuiTestApplication
