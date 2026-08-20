import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.protobuf)
    id("dev.detekt") version ("2.0.0-alpha.5")
    id("org.jlleitschuh.gradle.ktlint") version ("14.2.0")
}

@CacheableTask
abstract class GenerateLegalAssets @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val projectLicense: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thirdPartyNotices: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyLicenses: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val xrayLicense: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val xrayMetadata: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        fileSystemOperations.sync {
            into(outputDirectory)
            from(thirdPartyNotices) {
                into("legal")
            }
            from(projectLicense) {
                into("legal/licenses")
                rename { "GPL-3.0-or-later.txt" }
            }
            from(thirdPartyLicenses) {
                into("legal/licenses")
            }
            from(xrayLicense) {
                into("legal/licenses")
                rename { "MPL-2.0.txt" }
            }
            from(xrayMetadata) {
                exclude("LICENSE")
                into("legal/xray")
            }
        }
    }
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun localProperty(name: String): String? = localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val releaseKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH")
    .orElse(providers.gradleProperty("releaseKeystorePath"))
    .orNull
    ?: localProperty("releaseKeystorePath")
val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS")
    .orElse(providers.gradleProperty("releaseKeyAlias"))
    .orNull
    ?: localProperty("releaseKeyAlias")
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD")
    .orElse(providers.gradleProperty("releaseKeyPassword"))
    .orNull
    ?: localProperty("releaseKeyPassword")
val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD")
    .orElse(providers.gradleProperty("releaseStorePassword"))
    .orNull
    ?: localProperty("releaseStorePassword")
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeyAlias,
    releaseKeyPassword,
    releaseStorePassword,
).all { !it.isNullOrBlank() }
val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val grpcVersion = libsCatalog.findVersion("grpc").get().requiredVersion
val generateLegalAssets by tasks.registering(GenerateLegalAssets::class) {
    projectLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
    thirdPartyNotices.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
    thirdPartyLicenses.set(rootProject.layout.projectDirectory.dir("third_party/licenses"))
    xrayLicense.set(rootProject.layout.projectDirectory.file("third_party/xray/LICENSE"))
    xrayMetadata.set(rootProject.layout.projectDirectory.dir("third_party/xray"))
    outputDirectory.set(layout.buildDirectory.dir("generated/legalAssets"))
}

android {
    namespace = "com.material.xray"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.material.xray"
        minSdk = 28
        targetSdk = 36
        versionCode = 700
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(requireNotNull(releaseKeystorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Analysing the test sources costs more than the rest of the build put together, and it
        // reruns on every main-source edit because the test classes depend on them. detekt and
        // ktlint already cover the test sources, and the Android-specific checks lint adds are
        // about shipped code.
        ignoreTestSources = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Room exports one JSON schema per database version. They are committed so that
// DatabaseMigrationChainTest can replay the migration chain and compare the result against the
// schema Room generates from the entities.
val roomSchemaDirectory = layout.projectDirectory.dir("schemas")

ksp {
    arg("room.schemaLocation", roomSchemaDirectory.asFile.path)
}

tasks.withType<Test>().configureEach {
    systemProperty("room.schemaLocation", roomSchemaDirectory.asFile.path)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    basePath.set(rootDir)
}

ktlint {
    ignoreFailures.set(false)
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.HTML)
    }
    filter {
        // KSP (Hilt/Room) and protobuf/grpc write Kotlin/Java into build/generated;
        // those are attached to the source sets, so exclude them from linting.
        exclude { element -> element.file.path.contains("/generated/") }
    }
}

// Formatting is a source mutation, so it does not belong on the path that compiles the sources:
// rewriting a file mid-build invalidates the up-to-date checks of everything downstream, and it
// races an editor that has the same file open. The prek hook formats on commit, and `check` still
// enforces it here.
tasks.named("check") {
    dependsOn("ktlintFormat")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateLegalAssets,
            GenerateLegalAssets::outputDirectory,
        )
    }
}

protobuf {
    protoc {
        // Match grpc-protobuf-lite's 3.x javalite runtime; protobuf 4.x jars currently break Hilt metadata parsing.
        artifact = "com.google.protobuf:protoc:3.25.8"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    compileOnly(libs.tomcat.annotations.api)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.test.runner)
}
