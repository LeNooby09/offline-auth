import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	kotlin("jvm") version "2.3.10"
	kotlin("plugin.serialization") version "2.3.10"
	id("fabric-loom") version "1.15-SNAPSHOT"
	id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
	archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 21
java {
	toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("offline-auth") {
			sourceSet("main")
			sourceSet("client")
		}
	}
}

fabricApi {
	configureDataGeneration {
		client = true
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
	modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

	modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

	implementation("org.xerial:sqlite-jdbc:3.49.1.0")
	include(implementation("org.postgresql:postgresql:42.7.5")!!)
	include(implementation("com.zaxxer:HikariCP:6.2.1")!!)
	include("org.slf4j:slf4j-api:2.0.16")
	include(implementation("at.favre.lib:bcrypt:0.10.2")!!)
	include("at.favre.lib:bytes:1.5.0")
	include(implementation("io.nayuki:qrcodegen:1.8.0")!!)

	// Ktor embedded web server (CIO engine to avoid Netty conflicts with Minecraft)
	val ktorVersion = "3.4.0"
	include(implementation("io.ktor:ktor-server-core:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-server-cio:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-network:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-http-cio:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-serialization-kotlinx:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-server-cors:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-server-host-common:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-events:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-http:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-utils:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-io:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-serialization:$ktorVersion")!!)
	include(implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.6.0")!!)

	// Ktor HTTP client (for ATProto OAuth flow)
	include(implementation("io.ktor:ktor-client-core:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-client-cio:$ktorVersion")!!)
	include(implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")!!)

	// Nimbus JOSE+JWT (for DPoP key generation/proof in ATProto OAuth)
	include(implementation("com.nimbusds:nimbus-jose-jwt:10.0.1")!!)

	// Test dependencies (only used during `gradle test`).
	testImplementation(kotlin("test"))
	testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

val generateJdbcServiceFile by tasks.registering {
	val outputFile = layout.buildDirectory.file("generated/jdbc-service/META-INF/services/java.sql.Driver")
	outputs.file(outputFile)
	doLast {
		val f = outputFile.get().asFile
		f.parentFile.mkdirs()
		f.writeText("org.sqlite.JDBC\norg.postgresql.Driver\n", Charsets.UTF_8)
	}
}

tasks.jar {
	dependsOn(generateJdbcServiceFile)
	from({
		configurations.runtimeClasspath.get()
			.filter { it.name.contains("sqlite-jdbc") || it.name.contains("postgresql") }
			.map { zipTree(it) }
	}) {
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
		exclude("META-INF/MANIFEST.MF")
		exclude("META-INF/*.SF")
		exclude("META-INF/*.DSA")
		exclude("META-INF/*.RSA")
		exclude("META-INF/services/java.sql.Driver")
	}
	from(layout.buildDirectory.dir("generated/jdbc-service"))
}

tasks.processResources {
	inputs.property("version", project.version)
	inputs.property("minecraft_version", project.property("minecraft_version"))
	inputs.property("loader_version", project.property("loader_version"))
	filteringCharset = "UTF-8"

	filesMatching("fabric.mod.json") {
		expand(
			"version" to project.version,
			"minecraft_version" to project.property("minecraft_version") as String,
			"loader_version" to project.property("loader_version") as String,
			"kotlin_loader_version" to project.property("kotlin_loader_version") as String
		)
	}
}

tasks.withType<JavaCompile>().configureEach {
	// ensure that the encoding is set to UTF-8, no matter what the system default is
	// this fixes some edge cases with special characters not displaying correctly
	// see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
	// If Javadoc is generated, this must be specified in that task too.
	options.encoding = "UTF-8"
	options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
	compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
	from("LICENSE") {
		rename { "${it}_${project.base.archivesName.get()}" }
	}
}

// configure the maven publication
publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			artifactId = project.property("archives_base_name") as String
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
