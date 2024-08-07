/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package io.ballerina.plugin

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip

class BallerinaExtension {

    String module
    String langVersion
    String testCoverageParam
    String packageOrganization
    String customTomlVersion
    String platform
    boolean isConnector = false
}

class BallerinaPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def ballerinaExtension = project.extensions.create('ballerina', BallerinaExtension)

        def packageOrg = ''
        def platform = 'java17'
        def tomlVersion
        def balBuildTarget = 'build/bal_build_target'
        def balaArtifact = new File("$project.projectDir/build/bala_unzipped/")
        def projectDirectory = new File("$project.projectDir")
        def ballerinaCentralAccessToken = System.getenv('BALLERINA_CENTRAL_ACCESS_TOKEN')
        def groupParams = ''
        def disableGroups = ''
        def debugParams = ''
        def balJavaDebugParam = ''
        def testCoverageParams = ''
        def needPublishToCentral = false
        def needPublishToLocalCentral = false
        def graalvmFlag = ''
        def parallelTestFlag = ''
        def distributionBinPath = ''
        def ignoreVersionMismatch = false

        if (project.version.matches(project.ext.timestampedVersionRegex)) {
            def splitVersion = project.version.split('-')
            if (splitVersion.length > 3) {
                def strippedValues = splitVersion[0..-4]
                tomlVersion = strippedValues.join('-')
            } else {
                tomlVersion = project.version
            }
        } else {
            tomlVersion = project.version.replace("${project.ext.snapshotVersion}", '')
        }

        project.configurations {
            jbalTools
        }

        project.tasks.register('copyToLib', Copy.class) {
            if (project.configurations.find { it.name == "externalJars" }) {
                into "$project.projectDir/lib"
                from project.configurations.externalJars
            }
        }

        project.dependencies {
            if (ballerinaExtension.isConnector) {
                println("[Warning] skip downloading jBallerinaTools dependency: project uses locally installed Ballerina distribution to build the module")
                return
            }

            if (ballerinaExtension.langVersion != null) {
                jbalTools("org.ballerinalang:jballerina-tools:${ballerinaExtension.langVersion}") {
                    transitive = false
                }
            }
        }

        project.tasks.register('unpackJballerinaTools') {
            project.configurations.each { configuration ->
                if (configuration.name == "externalJars") {
                    dependsOn(project.copyToLib)
                }
            }

            doLast {
                project.configurations.jbalTools.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                    project.copy {
                        from project.zipTree(artifact.getFile())
                        into new File("${project.buildDir}/")
                    }

                    project.copy {
                        from(project.zipTree(artifact.getFile())) {
                            eachFile { fcd ->
                                fcd.relativePath = new RelativePath(!fcd.file.isDirectory(), fcd.relativePath.segments.drop(1))
                            }
                            includeEmptyDirs = false
                        }
                        into "${project.rootDir}/target/ballerina-runtime"
                    }
                }
            }
        }

        project.tasks.register('unpackStdLibs') {
            dependsOn(project.unpackJballerinaTools)

            doLast {
                project.configurations.ballerinaStdLibs.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                    project.copy {
                        from project.zipTree(artifact.getFile())
                        into new File("${project.buildDir}/extracted-stdlibs", artifact.name + '-zip')
                    }
                }
            }
        }

        project.tasks.register('copyStdlibs') {
            dependsOn(project.unpackStdLibs)

            doLast {
                /* Standard Libraries */
                project.configurations.ballerinaStdLibs.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                    def artifactExtractedPath = "${project.buildDir}/extracted-stdlibs/" + artifact.name + '-zip'
                    project.copy {
                        def ballerinaDist = "build/jballerina-tools-${ballerinaExtension.langVersion}"
                        into ballerinaDist
                        into('repo/bala') {
                            from "${artifactExtractedPath}/bala"
                        }
                        into('repo/cache') {
                            from "${artifactExtractedPath}/cache"
                        }
                    }
                    project.copy {
                        def runtimePath = "${project.rootDir}/target/ballerina-runtime"
                        into runtimePath
                        into('repo/bala') {
                            from "${artifactExtractedPath}/bala"
                        }
                        into('repo/cache') {
                            from "${artifactExtractedPath}/cache"
                        }
                    }
                }
            }
        }

        project.tasks.register('initializeVariables') {
            String packageName = ballerinaExtension.module
            String organization
            if (ballerinaExtension.packageOrganization == null) {
                organization = 'ballerina'
            } else {
                organization = ballerinaExtension.packageOrganization
            }
            if (project.hasProperty('groups')) {
                groupParams = "--groups ${project.findProperty('groups')}"
            }
            if (project.hasProperty('disable')) {
                disableGroups = "--disable-groups ${project.findProperty('disable')}"
            }
            if (project.hasProperty('debug')) {
                debugParams = "--debug ${project.findProperty('debug')}"
            }
            if (project.hasProperty('balJavaDebug')) {
                balJavaDebugParam = "BAL_JAVA_DEBUG=${project.findProperty('balJavaDebug')}"
            }
            if (project.hasProperty('publishToLocalCentral') && (project.findProperty('publishToLocalCentral') == 'true')) {
                needPublishToLocalCentral = true
            }
            if (project.hasProperty('publishToCentral') && (project.findProperty('publishToCentral') == 'true')) {
                needPublishToCentral = true
            }
            if (project.hasProperty('balGraalVMTest')) {
                println("[Warning] disabled code coverage: running GraalVM tests")
                graalvmFlag = '--graalvm'
            }
            if (project.hasProperty('balParallelTest')) {
                parallelTestFlag = '--parallel'
            }
            if (!ballerinaExtension.isConnector) {
                distributionBinPath = project.projectDir.absolutePath + "/build/jballerina-tools-${ballerinaExtension.langVersion}/bin"
            }

            if (!project.hasProperty('balGraalVMTest')) {
                if (ballerinaExtension.testCoverageParam == null) {
                    testCoverageParams = "--code-coverage --coverage-format=xml --includes=io.ballerina.stdlib.${packageName}.*:${organization}.${packageName}.*"
                } else {
                    testCoverageParams = ballerinaExtension.testCoverageParam
                }
            }

            if (project.hasProperty("ignoreVersionMismatch")) {
                ignoreVersionMismatch = true
            }
        }

        project.tasks.register("verifyLocalBalVersion") {
            dependsOn(project.initializeVariables)

            if (!ballerinaExtension.isConnector) {
                return
            }

            def output = new ByteArrayOutputStream()
            def error = new ByteArrayOutputStream()

            try {
                project.exec {
                    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                        commandLine 'cmd', '/c', "bal.bat -v"
                    } else {
                        commandLine 'sh', '-c', "bal -v"
                    }
                    standardOutput = output
                    errorOutput = error
                    ignoreExitValue = true
                }
            } catch (Exception e) {
                throw new GradleException("Ballerina installation not found, hence exiting", e)
            }

            def versionOutput = output.toString()
            def balVersion = versionOutput.split("\n")[0]
            def installedVersion = balVersion
                    .replaceAll("Ballerina ", '')
                    .replaceAll("\\(Swan Lake Update \\d+\\)", '')
                    .replaceAll("\\s+", '')

            def balVersionPattern = ~/^\d{4}\.\d+\.\d+$/
            if (!balVersionPattern.matcher(installedVersion).matches()) {
                throw new GradleException("Failed to parse the Ballerina version. Balllerina CLI output: $versionOutput Extracted version:$installedVersion")
            }

            def configuredVersion = project.findProperty('ballerinaLangVersion')
            if (installedVersion != configuredVersion) {
                if (ignoreVersionMismatch) {
                    println("[Warning] Ignoring Ballerina version mismatch. Expected: $configuredVersion, but found: $installedVersion.")
                    return
                }
                throw new GradleException("Ballerina version mismatch. Expected: $configuredVersion, but found: $installedVersion, hence exiting")
            }
        }

        project.tasks.register('build') {
            dependsOn(project.verifyLocalBalVersion)
            dependsOn(project.updateTomlFiles)
            finalizedBy(project.commitTomlFiles)
            dependsOn(project.test)
            doNotTrackState("build needs to run every time")

            inputs.dir projectDirectory
            doLast {
                String packageName = ballerinaExtension.module
                String balaVersion
                if (ballerinaExtension.customTomlVersion == null) {
                    balaVersion = tomlVersion
                } else {
                    balaVersion = ballerinaExtension.customTomlVersion
                }

                if (ballerinaExtension.platform != null) {
                    platform = ballerinaExtension.platform
                }

                if (ballerinaExtension.packageOrganization == null) {
                    packageOrg = 'ballerina'
                } else {
                    packageOrg = ballerinaExtension.packageOrganization
                }

                // Pack bala first
                project.exec {
                    workingDir project.projectDir
                    environment 'JAVA_OPTS', '-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true'
                    if (ballerinaExtension.isConnector) {
                        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                            commandLine 'cmd', '/c', "bal.bat pack --target-dir ${balBuildTarget} && exit %%ERRORLEVEL%%"
                        } else {
                            commandLine 'sh', '-c', "bal pack --target-dir ${balBuildTarget}"
                        }
                    } else {
                        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                            commandLine 'cmd', '/c', "$distributionBinPath/bal.bat pack --target-dir ${balBuildTarget} --offline && exit %%ERRORLEVEL%%"
                        } else {
                            commandLine 'sh', '-c', "$distributionBinPath/bal pack --target-dir ${balBuildTarget} --offline"
                        }
                    }
                }

                // extract bala file to balaArtifact
                new File("$project.projectDir/${balBuildTarget}/bala").eachFileMatch(~/.*.bala/) { balaFile ->
                    project.copy {
                        from project.zipTree(balaFile)
                        into new File("$balaArtifact/bala/${packageOrg}/${packageName}/${balaVersion}/${platform}")
                    }
                }
                project.copy {
                    from "$balaArtifact/bala"
                    into "${project.rootDir}/target/ballerina-runtime/repo/bala"
                }
                if (needPublishToCentral) {
                    if (project.version.endsWith('-SNAPSHOT') ||
                            project.version.matches(project.ext.timestampedVersionRegex)) {
                        println("[Info] skipping publishing to central: project version is SNAPSHOT or Timestamped SNAPSHOT")
                        return
                    }
                    if (ballerinaCentralAccessToken != null) {
                        project.exec {
                            workingDir project.projectDir
                            environment 'JAVA_OPTS', '-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true'
                            if (ballerinaExtension.isConnector) {
                                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                    commandLine 'cmd', '/c', "bal.bat push ${balBuildTarget}/bala/${packageOrg}-${packageName}-${platform}-${balaVersion}.bala && exit %%ERRORLEVEL%%"
                                } else {
                                    commandLine 'sh', '-c', "bal push ${balBuildTarget}/bala/${packageOrg}-${packageName}-${platform}-${balaVersion}.bala"
                                }
                            } else if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "$distributionBinPath/bal.bat push ${balBuildTarget}/bala/${packageOrg}-${packageName}-${platform}-${balaVersion}.bala && exit %%ERRORLEVEL%%"
                            } else {
                                commandLine 'sh', '-c', "$distributionBinPath/bal push ${balBuildTarget}/bala/${packageOrg}-${packageName}-${platform}-${balaVersion}.bala"
                            }
                        }
                    } else {
                        throw new InvalidUserDataException('Central Access Token is not present')
                    }
                } else if (needPublishToLocalCentral) {
                    println("[Info] Publishing to the ballerina local central repository")
                    project.exec {
                        workingDir project.projectDir
                        environment 'JAVA_OPTS', '-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true'
                        if (ballerinaExtension.isConnector) {
                            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "bal.bat push ${balBuildTarget}/bala/${packageOrg}-${packageName}-${platform}-${balaVersion}.bala --repository=local && exit %%ERRORLEVEL%%"
                            } else {
                                commandLine 'sh', '-c', "bal push ${balBuildTarget}/bala/${packageOrg}-${packageName}-${platform}-${balaVersion}.bala --repository=local"
                            }
                        } else {
                            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "$distributionBinPath/bal.bat push ${balBuildTarget}/bala/${packageOrg}-${packageName}-${platform}-${balaVersion}.bala --repository=local && exit %%ERRORLEVEL%%"
                            } else {
                                commandLine 'sh', '-c', "$distributionBinPath/bal push ${balBuildTarget}/bala/${packageOrg}-${packageName}-${platform}-${balaVersion}.bala --repository=local"
                            }
                        }
                    }
                }
            }
            outputs.dir balaArtifact
        }

        project.tasks.register('createArtifactZip', Zip.class) {
            destinationDirectory = new File("$project.buildDir/distributions")
            from project.build
        }

        project.tasks.register('test') {
            dependsOn(project.verifyLocalBalVersion)
            dependsOn(project.updateTomlFiles)
            finalizedBy(project.commitTomlFiles)
            doLast {
                // Run tests
                project.exec {
                    workingDir project.projectDir
                    environment 'JAVA_OPTS', '-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true'
                    if (ballerinaExtension.isConnector) {
                        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                            commandLine 'cmd', '/c', "bal.bat test --offline ${graalvmFlag} ${parallelTestFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams} && exit %%ERRORLEVEL%%"
                        } else {
                            commandLine 'sh', '-c', "bal test --offline ${graalvmFlag} ${parallelTestFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams}"
                        }
                    } else if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                        commandLine 'cmd', '/c', "$balJavaDebugParam $distributionBinPath/bal.bat test --offline ${graalvmFlag} ${parallelTestFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams} && exit %%ERRORLEVEL%%"
                    } else {
                        commandLine 'sh', '-c', "$balJavaDebugParam $distributionBinPath/bal test --offline ${graalvmFlag} ${parallelTestFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams}"
                    }

                }
            }
        }

        project.tasks.register('clean', Delete.class) {
            delete "$project.projectDir/target"
            delete "$project.projectDir/build"
            delete "$project.rootDir/target"
        }
    }
}
