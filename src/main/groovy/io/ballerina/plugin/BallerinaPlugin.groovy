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
    String buildOnDockerImage
    String testCoverageParam
    String packageOrganization
    String customTomlVersion
    String platform
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
        def parentDirectory = new File("$projectDirectory.parent")
        def ballerinaCentralAccessToken = System.getenv('BALLERINA_CENTRAL_ACCESS_TOKEN')
        def groupParams = ''
        def disableGroups = ''
        def debugParams = ''
        def balJavaDebugParam = ''
        def testCoverageParams = ''
        def needSeparateTest = false
        def needBuildWithTest = false
        def needPublishToCentral = false
        def needPublishToLocalCentral = false
        def skipTests = true
        def graalvmFlag = ''
        def buildOnDocker = false
        def ballerinaDockerTag = ''

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

        project.afterEvaluate {
            if (ballerinaExtension.buildOnDockerImage != null) {
                buildOnDocker = true
                ballerinaDockerTag = ballerinaExtension.buildOnDockerImage
            }

            if (project.hasProperty('buildUsingDocker')) {
                buildOnDocker = true
                ballerinaDockerTag = project.findProperty('buildUsingDocker')
            }

            if (ballerinaDockerTag == '') {
                ballerinaDockerTag = 'nightly'
            }

            if (buildOnDocker) {
                println("[Info] project builds on docker: $buildOnDocker")
                println("[Info] using the Ballerina docker image tag: $ballerinaDockerTag")
            }
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
            if (buildOnDocker) {
                println("[Warning] skipping downloading jBallerinaTools dependency: project uses docker to build the module")
            } else {
                if (ballerinaExtension.langVersion == null) {
                    jbalTools("org.ballerinalang:jballerina-tools:${ballerinaExtension.langVersion}") {
                        transitive = false
                    }
                } else {
                    jbalTools("org.ballerinalang:jballerina-tools:${ballerinaExtension.langVersion}") {
                        transitive = false
                    }
                }
            }
        }

        project.tasks.register('unpackJballerinaTools') {
            project.configurations.each { configuration ->
                if (configuration.name == "externalJars") {
                    dependsOn(project.copyToLib)
                }
            }

            if (buildOnDocker) {
                println("[Warning] skipping task 'unpackJballerinaTools': project uses docker to build the module")
            } else {
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
        }

        project.tasks.register('unpackStdLibs') {
            dependsOn(project.unpackJballerinaTools)
            if (buildOnDocker) {
                println("[Warning] skipping task 'unpackStdLibs': project uses docker to build the module")
            } else {
                doLast {
                    project.configurations.ballerinaStdLibs.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                        project.copy {
                            from project.zipTree(artifact.getFile())
                            into new File("${project.buildDir}/extracted-stdlibs", artifact.name + '-zip')
                        }
                    }
                }
            }
        }

        project.tasks.register('copyStdlibs') {
            dependsOn(project.unpackStdLibs)
            if (buildOnDocker) {
                println("[Warning] skipping task 'copyStdlibs': project uses docker to build the module")
            } else {
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

            project.gradle.taskGraph.whenReady { graph ->
                if (!(project.hasProperty('disable') || project.hasProperty('groups')) &&
                        (graph.hasTask(":${packageName}-ballerina:build") ||
                                graph.hasTask(":${packageName}-ballerina:publish") ||
                                graph.hasTask(":${packageName}-ballerina:publishToMavenLocal"))) {
                    needSeparateTest = false
                    needBuildWithTest = true
                } else {
                    needSeparateTest = true
                }
                if (graph.hasTask(":${packageName}-ballerina:test")) {
                    if (!project.hasProperty('balGraalVMTest')) {
                        if (ballerinaExtension.testCoverageParam == null) {
                            testCoverageParams = "--code-coverage --coverage-format=xml --includes=io.ballerina.stdlib.${packageName}.*:${organization}.${packageName}.*"
                        } else {
                            testCoverageParams = ballerinaExtension.testCoverageParam
                        }
                    }
                    skipTests = false;
                }
            }
        }

        project.tasks.register('build') {
            dependsOn(project.initializeVariables)
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
                if (needBuildWithTest) {
                    // Pack bala first
                    project.exec {
                        workingDir project.projectDir
                        environment 'JAVA_OPTS', '-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true'
                        if (buildOnDocker) {
                            String dockerTag = ballerinaExtension.buildOnDockerImage
                            if (dockerTag != null && dockerTag != '') {
                                ballerinaDockerTag = dockerTag
                            }
                            def balPackWithDocker = """
                                docker run --rm --net=host --user \$(id -u):\$(id -g) \
                                    -v $parentDirectory:/home/ballerina/$parentDirectory.name \
                                    -v $projectDirectory:/home/ballerina/$parentDirectory.name/$projectDirectory.name \
                                    -w /home/ballerina \
                                    ballerina/ballerina:$ballerinaDockerTag \
                                    /bin/sh -c "cd $parentDirectory.name/$projectDirectory.name" && \
                                    $balJavaDebugParam bal pack --target-dir ${balBuildTarget} ${debugParams}
                            """
                            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "$balPackWithDocker"
                            } else {
                                commandLine 'sh', '-c', "$balPackWithDocker"
                            }
                        } else {
                            String distributionBinPath = project.projectDir.absolutePath + "/build/jballerina-tools-${ballerinaExtension.langVersion}/bin"
                            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "$balJavaDebugParam $distributionBinPath/bal.bat pack --target-dir ${balBuildTarget} --offline ${debugParams} && exit %%ERRORLEVEL%%"
                            } else {
                                commandLine 'sh', '-c', "$balJavaDebugParam $distributionBinPath/bal pack --target-dir ${balBuildTarget} --offline ${debugParams}"
                            }
                        }
                    }
                    // Run tests
                    if (!skipTests) {
                        project.exec {
                            workingDir project.projectDir
                            environment 'JAVA_OPTS', '-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true'
                            if (buildOnDocker) {
                                String dockerTag = ballerinaExtension.buildOnDockerImage
                                if (dockerTag != null && dockerTag != '') {
                                    ballerinaDockerTag = dockerTag
                                }
                                def balTestWithDocker = """
                                    docker run --rm --net=host --user \$(id -u):\$(id -g) \
                                        -v $parentDirectory:/home/ballerina/$parentDirectory.name \
                                        -v $projectDirectory:/home/ballerina/$parentDirectory.name/$projectDirectory.name \
                                        -w /home/ballerina \
                                        ballerina/ballerina:$ballerinaDockerTag \
                                        /bin/sh -c "cd $parentDirectory.name/$projectDirectory.name" && \
                                        bal test ${graalvmFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams}
                                """
                                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                    commandLine 'cmd', '/c', "$balTestWithDocker"
                                } else {
                                    commandLine 'sh', '-c', "$balTestWithDocker"
                                }
                            } else if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "$balJavaDebugParam $distributionBinPath/bal.bat test --offline ${graalvmFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams} && exit %%ERRORLEVEL%%"
                            } else {
                                commandLine 'sh', '-c', "$balJavaDebugParam $distributionBinPath/bal test --offline ${graalvmFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams}"
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
                                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
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
            dependsOn(project.initializeVariables)
            dependsOn(project.updateTomlFiles)
            finalizedBy(project.commitTomlFiles)
            doLast {
                if (needSeparateTest) {
                    project.exec {
                        workingDir project.projectDir
                        environment 'JAVA_OPTS', '-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true'
                        def balTestWithDocker = """
                            docker run --rm --net=host --user \$(id -u):\$(id -g) \
                                -v $parentDirectory:/home/ballerina/$parentDirectory.name \
                                -v $projectDirectory:/home/ballerina/$parentDirectory.name/$projectDirectory.name \
                                -w /home/ballerina \
                                ballerina/ballerina:$ballerinaDockerTag \
                                /bin/sh -c "cd $parentDirectory.name/$projectDirectory.name" && \
                                bal test ${graalvmFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams}
                        """
                        if (buildOnDocker) {
                            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "$balTestWithDocker"
                            } else {
                                commandLine 'sh', '-c', "$balTestWithDocker"
                            }
                        } else {
                            String distributionBinPath = project.projectDir.absolutePath + "/build/jballerina-tools-${ballerinaExtension.langVersion}/bin"
                            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "${balJavaDebugParam} ${distributionBinPath}/bal.bat test --offline ${graalvmFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams} && exit %%ERRORLEVEL%%"
                            } else {
                                commandLine 'sh', '-c', "${balJavaDebugParam} ${distributionBinPath}/bal test --offline ${graalvmFlag} ${testCoverageParams} ${groupParams} ${disableGroups} ${debugParams}"
                            }
                        }
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
