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
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip

class BallerinaExtension {
    String module
    String langVersion
    String testCoverageParam
    String packageOrganization
}

class BallerinaPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {

        project.extensions.create("ballerina", BallerinaExtension)

        project.configurations {
            jbalTools
        }

        project.dependencies {
            if(project.extensions.ballerina.langVersion==null){
                jbalTools ("org.ballerinalang:jballerina-tools:${project.ballerinaLangVersion}") {
                    transitive = false
                }
            }else{
                jbalTools ("org.ballerinalang:jballerina-tools:${project.extensions.ballerina.langVersion}") {
                    transitive = false
                }
            }
        }

        def platform = "java11"
        def tomlVersion
        def artifactBallerinaDocs = new File("$project.projectDir/build/docs_parent/")
        def artifactCacheParent = new File("$project.projectDir/build/cache_parent/")
        def artifactLibParent = new File("$project.projectDir/build/lib_parent/")
        def projectDirectory = new File("$project.projectDir")
        def ballerinaCentralAccessToken = System.getenv('BALLERINA_CENTRAL_ACCESS_TOKEN')
        def groupParams = ""
        def disableGroups = ""
        def debugParams = ""
        def balJavaDebugParam = ""
        def testParams = ""
        def needSeparateTest = false
        def needBuildWithTest = false
        def needPublishToCentral = false
        def needPublishToLocalCentral = false
        def packageOrg = ""

        if (project.version.matches(project.ext.timestampedVersionRegex)) {
            def splitVersion = project.version.split('-');
            if (splitVersion.length > 3) {
                def strippedValues = splitVersion[0..-4]
                tomlVersion = strippedValues.join('-')
            } else {
                tomlVersion = project.version
            }
        } else {
            tomlVersion = project.version.replace("${project.ext.snapshotVersion}", "")
        }

        project.tasks.register("copyToLib", Copy.class){
            into "$project.projectDir/lib"
            from project.configurations.externalJars
        }

        project.tasks.register("unpackJballerinaTools", Copy.class){
            String module = project.extensions.ballerina.module
            project.configurations.each {configuration ->
                if(configuration.toString().equals("configuration ':"+module+"-ballerina:externalJars'")){
                    dependsOn(project.copyToLib)
                }
            }
            project.configurations.jbalTools.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                from project.zipTree(artifact.getFile())
                into new File("${project.buildDir}/target/extracted-distributions", "jballerina-tools-zip")
            }
        }

        project.tasks.register("unpackStdLibs"){
            dependsOn(project.unpackJballerinaTools)
            doLast {
                project.configurations.ballerinaStdLibs.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                    project.copy {
                        from project.zipTree(artifact.getFile())
                        into new File("${project.buildDir}/target/extracted-distributions", artifact.name + "-zip")
                    }
                }
            }
        }

        project.tasks.register("copyStdlibs", Copy.class){
            dependsOn(project.unpackStdLibs)
            def ballerinaDist = "build/target/extracted-distributions/jballerina-tools-zip/jballerina-tools-${project.ballerinaLangVersion}"
            into ballerinaDist

            /* Standard Libraries */
            project.configurations.ballerinaStdLibs.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                def artifactExtractedPath = "${project.buildDir}/target/extracted-distributions/" + artifact.name + "-zip"
                into("repo/bala") {
                    from "${artifactExtractedPath}/bala"
                }
                into("repo/cache") {
                    from "${artifactExtractedPath}/cache"
                }

            }
        }

        project.tasks.register("initializeVariables"){

            String packageName = project.extensions.ballerina.module

            if (project.hasProperty("groups")) {
                groupParams = "--groups ${project.findProperty("groups")}"
            }
            if (project.hasProperty("disable")) {
                disableGroups = "--disable-groups ${project.findProperty("disable")}"
            }
            if (project.hasProperty("debug")) {
                debugParams = "--debug ${project.findProperty("debug")}"
            }
            if (project.hasProperty("balJavaDebug")) {
                balJavaDebugParam = "BAL_JAVA_DEBUG=${project.findProperty("balJavaDebug")}"
            }
            if (project.hasProperty("publishToLocalCentral") && (project.findProperty("publishToLocalCentral") == "true")) {
                needPublishToLocalCentral = true
            }
            if (project.hasProperty("publishToCentral") && (project.findProperty("publishToCentral") == "true")) {
                needPublishToCentral = true
            }

            project.gradle.taskGraph.whenReady { graph ->
                if (graph.hasTask(":${packageName}-ballerina:build") || graph.hasTask(":${packageName}-ballerina:publish") ||
                        graph.hasTask(":${packageName}-ballerina:publishToMavenLocal")) {
                    needSeparateTest = false
                    needBuildWithTest = true
                    if (graph.hasTask(":${packageName}-ballerina:publish")) {
                        needPublishToCentral = true
                    }
                } else {
                    needSeparateTest = true
                }
                if (graph.hasTask(":${packageName}-ballerina:test")) {
                    if(project.extensions.ballerina.testCoverageParam==null){
                        testParams = "--code-coverage --jacoco-xml --includes=org.ballerinalang.stdlib.${packageName}.*:ballerina.${packageName}.*"
                    }
                    else{
                        testParams = project.extensions.ballerina.testCoverageParam
                    }
                } else {
                    testParams = "--skip-tests"
                }
            }
        }

        project.tasks.register("build"){

            dependsOn(project.initializeVariables)
            dependsOn(project.updateTomlVersions)
            finalizedBy(project.revertTomlFile)
            dependsOn(project.test)

            inputs.dir projectDirectory
            doLast {
                String distributionBinPath = project.projectDir.absolutePath + "/build/target/extracted-distributions/jballerina-tools-zip/jballerina-tools-${project.extensions.ballerina.langVersion}/bin"
                String packageName = project.extensions.ballerina.module

                if(project.extensions.ballerina.packageOrganization==null){
                    packageOrg = "ballerina"
                }else{
                    packageOrg = project.extensions.ballerina.packageOrganization
                }
                if (needBuildWithTest) {
                    project.exec {
                        workingDir project.projectDir
                        environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                            commandLine 'cmd', '/c', "$balJavaDebugParam $distributionBinPath/bal.bat build -c ${testParams} ${debugParams} && exit %%ERRORLEVEL%%"
                        } else {
                            commandLine 'sh', '-c', "$balJavaDebugParam $distributionBinPath/bal build -c ${testParams} ${debugParams}"
                        }
                    }
                    // extract bala file to artifact cache directory
                    new File("$project.projectDir/target/bala").eachFileMatch(~/.*.bala/) { balaFile ->
                        project.copy {
                            from project.zipTree(balaFile)
                            into new File("$artifactCacheParent/bala/${packageOrg}/${packageName}/${tomlVersion}/${platform}")
                        }
                    }
                    project.copy {
                        from new File("$project.projectDir/target/cache")
                        exclude '**/*-testable.jar'
                        exclude '**/tests_cache/'
                        into new File("$artifactCacheParent/cache/")
                    }
                    // Doc creation and packing
                    project.exec {
                        workingDir project.projectDir
                        environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                            commandLine 'cmd', '/c', "$distributionBinPath/bal.bat doc && exit %%ERRORLEVEL%%"
                        } else {
                            commandLine 'sh', '-c', "$distributionBinPath/bal doc"
                        }
                    }
                    project.copy {
                        from new File("$project.projectDir/target/apidocs/${packageName}")
                        into new File("$project.projectDir/build/docs_parent/docs/${packageName}")
                    }
                    if (needPublishToCentral) {
                        if (project.version.endsWith('-SNAPSHOT') ||
                                project.version.matches(project.ext.timestampedVersionRegex)) {
                            println("The project version is SNAPSHOT or Timestamped SNAPSHOT, not publishing to central.")
                            return
                        }
                        if (ballerinaCentralAccessToken != null) {
                            println("Publishing to the ballerina central...")
                            project.exec {
                                workingDir project.projectDir
                                environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                    commandLine 'cmd', '/c', "$distributionBinPath/bal.bat push && exit %%ERRORLEVEL%%"
                                } else {
                                    commandLine 'sh', '-c', "$distributionBinPath/bal push"
                                }
                            }
                        } else {
                            throw new InvalidUserDataException("Central Access Token is not present")
                        }
                    } else if (needPublishToLocalCentral) {
                        println("Publishing to the ballerina local central repository..")
                        project.exec {
                            workingDir project.projectDir
                            environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "$distributionBinPath/bal.bat push && exit %%ERRORLEVEL%% --repository=local"
                            } else {
                                commandLine 'sh', '-c', "$distributionBinPath/bal push --repository=local"
                            }
                        }
                    }
                }
            }

            outputs.dir artifactCacheParent
            outputs.dir artifactBallerinaDocs
            outputs.dir artifactLibParent
        }

        project.tasks.register("createArtifactZip", Zip.class){
            destinationDirectory = new File("$project.buildDir/distributions")
            from project.build
        }

        project.tasks.register("test"){
            dependsOn(project.initializeVariables)
            dependsOn(project.updateTomlVersions)
            finalizedBy(project.revertTomlFile)
            doLast {
                if(needSeparateTest){
                    String distributionBinPath = project.projectDir.absolutePath + "/build/target/extracted-distributions/jballerina-tools-zip/jballerina-tools-${project.extensions.ballerina.langVersion}/bin"
                    String packageName = project.extensions.ballerina.module
                    project.exec {
                        workingDir project.projectDir
                        environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                            commandLine 'cmd', '/c', "${balJavaDebugParam} ${distributionBinPath}/bal.bat test ${testParams} ${groupParams} ${disableGroups} ${debugParams} && exit %%ERRORLEVEL%%"
                        } else {
                            commandLine 'sh', '-c', "${balJavaDebugParam} ${distributionBinPath}/bal test ${testParams} ${groupParams} ${disableGroups} ${debugParams}"
                        }
                    }
                }
            }
        }

        project.tasks.register("clean", Delete.class){
            delete "$project.projectDir/target"
            delete "$project.projectDir/build"
        }

    }
}
