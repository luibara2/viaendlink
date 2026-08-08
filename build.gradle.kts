plugins {
    id("java")
}

group = "org.endstone"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // The proxy, for its plugin API and protocol types. compileOnly on purpose: at runtime the addon
    // is loaded by the proxy, whose classloader is this one's parent, so packaging any of it would
    // mean two copies of every class and a very confusing ClassCastException.
    compileOnly(project(":"))
    compileOnly("org.cloudburstmc.protocol:bedrock-codec")
    compileOnly("org.cloudburstmc.protocol:bedrock-connection")

    testImplementation(project(":"))
    testImplementation("org.cloudburstmc.protocol:bedrock-codec")
    testImplementation("org.cloudburstmc.protocol:bedrock-connection")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * ViaEndlink.jar: the plugin classes plus the translator jar it drives, so an operator drops exactly
 * one file into `plugins/` — the Geyser model. JavaBridge extracts the embedded copy into its data
 * folder on first start.
 *
 * The bridge jar is built separately by `bridge/build.ps1`, which is also what verifies the
 * local patches actually made it in. If it is missing, this jar is still built and still works; it
 * just falls back to the ViaProxy.jar an operator placed in the data folder themselves.
 */
val bridgeJar = layout.projectDirectory.file("bridge/dist/ViaProxy.jar")

tasks.jar {
    archiveFileName.set("ViaEndlink.jar")
    destinationDirectory.set(layout.projectDirectory.dir("dist"))

    from(bridgeJar) {
        rename { "ViaProxy.jar" }
    }
    doFirst {
        if (!bridgeJar.asFile.exists()) {
            logger.warn(
                "bridge/dist/ViaProxy.jar is missing, so ViaEndlink.jar will not carry the " +
                    "translator. Run bridge/build.ps1 first, or place ViaProxy.jar in the addon's " +
                    "data folder on the server."
            )
        }
    }
}

tasks.test {
    useJUnitPlatform()
    for (key in listOf(
        "javax.net.ssl.trustStore",
        "javax.net.ssl.trustStorePassword",
        "javabridge.viaProxyJar"
    )) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
