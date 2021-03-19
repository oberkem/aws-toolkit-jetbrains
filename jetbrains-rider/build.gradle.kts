// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
import com.jetbrains.rd.generator.gradle.RdGenExtension
import com.jetbrains.rd.generator.gradle.RdGenTask
import org.jetbrains.intellij.tasks.PrepareSandboxTask
import software.aws.toolkits.gradle.IdeVersions
import java.nio.file.Path

buildscript {
    // Cannot be removed or else it will fail to compile
    @Suppress("RemoveRedundantQualifierName")
    val rdversion = software.aws.toolkits.gradle.IdeVersions.ideProfile(project).rider.rdGenVersion

    logger.info("Using rd-gen: $rdversion")

    repositories {
        maven("https://www.myget.org/F/rd-snapshots/maven/")
    }

    dependencies {
        classpath("com.jetbrains.rd:rd-gen:$rdversion")
    }
}

plugins {
    id("org.jetbrains.intellij")
}

apply(plugin = "com.jetbrains.rdgen")

val ideProfile = IdeVersions.ideProfile(project)

val resharperPluginPath = File(projectDir, "ReSharper.AWS")
val resharperBuildPath = File(project.buildDir, "dotnetBuild")

val buildConfiguration = project.extra.properties["BuildConfiguration"] ?: "Debug" // TODO: Do we ever want to make a release build?

// Protocol
val protocolGroup = "protocol"

val csDaemonGeneratedOutput = File(resharperPluginPath, "src/AWS.Daemon/Protocol")
val csPsiGeneratedOutput = File(resharperPluginPath, "src/AWS.Psi/Protocol")
val csAwsSettingsGeneratedOutput = File(resharperPluginPath, "src/AWS.Settings/Protocol")
val csAwsProjectGeneratedOutput = File(resharperPluginPath, "src/AWS.Project/Protocol")

val riderGeneratedSources = File("$buildDir/generated-src/software/aws/toolkits/jetbrains/protocol")

val modelDir = File(projectDir, "protocol/model")
val rdgenDir = File("${project.buildDir}/rdgen/")

rdgenDir.mkdirs()

intellij {
    pluginName = "aws-toolkit-jetbrains"

    version = ideProfile.rider.sdkVersion
    // Workaround for https://youtrack.jetbrains.com/issue/IDEA-179607
    val extraPlugins = arrayOf("rider-plugins-appender")
    setPlugins(*(ideProfile.rider.plugins + extraPlugins))

    // RD is closed source, so nothing to download.
    downloadSources = false
    instrumentCode = false
}

configure<RdGenExtension> {
    verbose = true
    hashFolder = rdgenDir.toString()
    logger.info("Configuring rdgen params")

    classpath({
        logger.info("Calculating classpath for rdgen, intellij.ideaDependency is: ${intellij.ideaDependency}")
        File(intellij.ideaDependency.classes, "lib/rd").resolve("rider-model.jar").absolutePath
    })

    sources(projectDir.resolve("protocol/model"))
    packages = "model"
}

val generateModels = tasks.register<RdGenTask>("generateModels") {
    group = protocolGroup
    description = "Generates protocol models"

    systemProperty("ktDaemonGeneratedOutput", riderGeneratedSources.resolve("DaemonProtocol").absolutePath)
    systemProperty("csDaemonGeneratedOutput", csDaemonGeneratedOutput.absolutePath)

    systemProperty("ktPsiGeneratedOutput", riderGeneratedSources.resolve("PsiProtocol").absolutePath)
    systemProperty("csPsiGeneratedOutput", csPsiGeneratedOutput.absolutePath)

    systemProperty("ktAwsSettingsGeneratedOutput", riderGeneratedSources.resolve("AwsSettingsProtocol").absolutePath)
    systemProperty("csAwsSettingsGeneratedOutput", csAwsSettingsGeneratedOutput.absolutePath)

    systemProperty("ktAwsProjectGeneratedOutput", riderGeneratedSources.resolve("AwsProjectProtocol").absolutePath)
    systemProperty("csAwsProjectGeneratedOutput", csAwsProjectGeneratedOutput.absolutePath)
}

val cleanGenerateModels = tasks.register("cleanGenerateModels") {
    group = protocolGroup
    description = "Clean up generated protocol models"

    logger.info("Deleting generated Kotlin files...")
    riderGeneratedSources.listFiles().orEmpty().forEach { it.deleteRecursively() }

    logger.info("Deleting generated CSharp files...")
    val csGeneratedRoots = listOf(
        csDaemonGeneratedOutput,
        csPsiGeneratedOutput,
        csAwsSettingsGeneratedOutput,
        csAwsProjectGeneratedOutput
    )

    csGeneratedRoots.forEach { protocolDirectory: File ->
        if (!protocolDirectory.exists()) return@forEach
        protocolDirectory.listFiles().orEmpty().forEach { file -> file.deleteRecursively() }
    }
}

val cleanNetBuilds = task("cleanNetBuilds", Delete::class) {
    group = protocolGroup
    description = "Clean up obj/ bin/ folders under ReSharper.AWS"
    delete(project.fileTree("ReSharper.AWS/") {
        include("**/bin/")
        include("**/obj/")
    })
}

project.tasks.clean {
    dependsOn(cleanGenerateModels, cleanNetBuilds)
}

// Backend
val backendGroup = "backend"

val prepareBuildProps = tasks.register("prepareBuildProps") {
    val riderSdkVersionPropsPath = File(resharperPluginPath, "RiderSdkPackageVersion.props")
    group = backendGroup

    inputs.property("riderNugetSdkVersion", ideProfile.rider.nugetVersion)
    outputs.file(riderSdkVersionPropsPath)

    doLast {
        val riderSdkVersion = ideProfile.rider.nugetVersion
        val configText = """<Project>
  <PropertyGroup>
    <RiderSDKVersion>[$riderSdkVersion]</RiderSDKVersion>
    <DefineConstants>PROFILE_${ideProfile.name.replace(".", "_")}</DefineConstants>
  </PropertyGroup>
</Project>
"""
        riderSdkVersionPropsPath.writeText(configText)
    }
}

val prepareNuGetConfig = tasks.register("prepareNuGetConfig") {
    group = backendGroup

    val nugetConfigPath = File(projectDir, "NuGet.Config")
    // FIX_WHEN_MIN_IS_211 remove the projectDir one above
    val nugetConfigPath211 = Path.of(projectDir.absolutePath, "testData", "NuGet.config").toFile()

    inputs.property("rdVersion", ideProfile.rider.sdkVersion)
    outputs.files(nugetConfigPath, nugetConfigPath211)

    doLast {
        val nugetPath = getNugetPackagesPath()
        val configText = """<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <add key="resharper-sdk" value="$nugetPath" />
  </packageSources>
</configuration>
"""
        nugetConfigPath.writeText(configText)
        nugetConfigPath211.writeText(configText)
    }
}

val buildReSharperPlugin = tasks.register("buildReSharperPlugin") {
    group = backendGroup
    description = "Builds the full ReSharper backend plugin solution"
    dependsOn(generateModels, prepareBuildProps, prepareNuGetConfig)

    inputs.dir(resharperPluginPath)
    outputs.dir(resharperBuildPath)

    outputs.files({
        fileTree(file("${resharperPluginPath.absolutePath}/src")).matching {
            include("**/bin/Debug/**/AWS*.dll")
            include("**/bin/Debug/**/AWS*.pdb")
        }
    })

    doLast {
        val arguments = listOf(
            "build",
            "${resharperPluginPath.canonicalPath}/ReSharper.AWS.sln"
        )
        exec {
            executable = "dotnet"
            args = arguments
        }
    }
}

fun getNugetPackagesPath(): File {
    val sdkPath = intellij.ideaDependency.classes
    println("SDK path: $sdkPath")

    // 2019
    var riderSdk = File(sdkPath, "lib/ReSharperHostSdk")
    // 2020.1
    if (!riderSdk.exists()) {
        riderSdk = File(sdkPath, "lib/DotNetSdkForRdPlugins")
    }

    println("NuGet packages: $riderSdk")
    if (!riderSdk.isDirectory) throw IllegalStateException("$riderSdk does not exist or not a directory")

    return riderSdk
}

dependencies {
    implementation(project(":jetbrains-core"))
    testImplementation(project(":jetbrains-core", "testArtifacts"))
}

sourceSets {
    main {
        java.srcDirs("$buildDir/generated-src")
    }
}

val resharperParts = listOf(
    "AWS.Daemon",
    "AWS.Localization",
    "AWS.Project",
    "AWS.Psi",
    "AWS.Settings"
)

// Tasks:
//
// `buildPlugin` depends on `prepareSandbox` task and then zips up the sandbox dir and puts the file in rider/build/distributions
// `runIde` depends on `prepareSandbox` task and then executes IJ inside the sandbox dir
// `prepareSandbox` depends on the standard Java `jar` and then copies everything into the sandbox dir

tasks.withType(PrepareSandboxTask::class.java).configureEach {
    dependsOn(buildReSharperPlugin)

    val files = resharperParts.map { "$resharperBuildPath/bin/$it/$buildConfiguration/${it}.dll" } +
        resharperParts.map { "$resharperBuildPath/bin/$it/$buildConfiguration/${it}.pdb" }
    from(files) {
        into("${intellij.pluginName}/dotnet")
    }
}

tasks.compileKotlin {
    dependsOn(generateModels)
}

tasks.test {
    systemProperty("log.dir", "${intellij.sandboxDirectory}-test/logs")
    useTestNG()
    environment("LOCAL_ENV_RUN", true)
    maxHeapSize = "1024m"
}

tasks.integrationTest {
    useTestNG()
    environment("LOCAL_ENV_RUN", true)
}

tasks.jar {
    archiveBaseName.set("aws-intellij-toolkit-rider")
}
