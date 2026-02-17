import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	kotlin("jvm") version "2.3.10"
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
	include(implementation("com.zaxxer:HikariCP:6.2.1")!!)
	include("org.slf4j:slf4j-api:2.0.16")
	include(implementation("at.favre.lib:bcrypt:0.10.2")!!)
	include("at.favre.lib:bytes:1.5.0")
}

tasks.jar {
	from({
		configurations.runtimeClasspath.get()
			.filter { it.name.contains("sqlite-jdbc") }
			.map { zipTree(it) }
	}) {
		exclude("META-INF/MANIFEST.MF")
		exclude("META-INF/*.SF")
		exclude("META-INF/*.DSA")
		exclude("META-INF/*.RSA")
	}
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
