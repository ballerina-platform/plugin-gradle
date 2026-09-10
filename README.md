# Ballerina Gradle plugin

## Introduction

The Ballerina Gradle plugin is used to build Ballerina modules using Gradle. This plugin is used in the Ballerina platform to build the Ballerina Libraries. This plugin is needed when there is a Gradle project with a Ballerina submodule. The Ballerina submodule can be built using this plugin.

>**Note:** This plugin is not recommended for building standalone Ballerina packages. It is recommended to use the Ballerina CLI directly in such scenarios.

## Prerequisites

* This plugin is published in the Ballerina platform GitHub packages repository. Therefore, the Ballerina platform GitHub packages repository should be added to the Gradle plugin management repositories. To access the Ballerina platform GitHub packages repository, a GitHub personal access token (PAT) should be provided. The PAT should be provided as an environment variable named `packagePAT`. The username of the GitHub account should be provided as an environment variable named `packageUser`.

## Usage

* Add the Ballerina Gradle plugin version in the `gradle.properties` file in your project.

  ```properties
  ballerinaGradlePluginVersion=1.0.0
  ```

* In your `settings.gradle` file, add the Ballerina Gradle Plugin under plugin management. Add the Ballerina platform GitHub packages repository under the repositories.

  ```groovy
  pluginManagement {
      plugins {
          id "io.ballerina.plugin" version "${ballerinaGradlePluginVersion}"
      }

      repositories {
          gradlePluginPortal() // To resolve the plugins from the Gradle plugin portal.
          maven { // To resolve the Ballerina plugin from the Ballerina platform GitHub packages repository.
              url = 'https://maven.pkg.github.com/ballerina-platform/*'
              credentials {
                  username System.getenv("packageUser")
                  password System.getenv("packagePAT")
              }
          }
      }
  }
  ```

* In your `build.gradle` file inside the `ballerina` submodule, apply the Ballerina Gradle plugin.

  ```groovy
  plugins {
      id "io.ballerina.plugin"
  }
  ```

* Provide the required inputs for the Ballerina plugin in the above `build.gradle` file.

  ```groovey
  ballerina {
    packageOrganization = "ballerina"
    module = "io"
    testCoverageParam = "--code-coverage --coverage-format=xml"
  }
  ```

## Toml file management

The plugin provides `updateTomlFiles` and `commitTomlFiles` tasks, wired into `build` and `test` (`dependsOn updateTomlFiles`, `finalizedBy commitTomlFiles`). Consumers no longer need to declare these tasks themselves.

* `updateTomlFiles` templates `build-config/resources/Ballerina.toml` (substituting `@project.version@` and `@toml.version@`) into the submodule's `Ballerina.toml`. If `build-config/resources/CompilerPlugin.toml` also exists, it is templated (substituting `@project.version@`) into `CompilerPlugin.toml`.
* `commitTomlFiles` commits `Ballerina.toml`, `Dependencies.toml`, and `CompilerPlugin.toml` (if present) with the message `[Automated] Update the toml files`.

>**Breaking change:** if your `build.gradle` still declares local `task updateTomlFiles { ... }` / `task commitTomlFiles { ... }` tasks (or an `ExecOperations`-injection helper such as `BallerinaExecHelper`), remove them before upgrading — otherwise the build fails with a duplicate-task-name error. This migration only covers `Ballerina.toml`/`CompilerPlugin.toml` templates that use no placeholders beyond `@project.version@`/`@toml.version@`; modules with additional module-specific placeholders (e.g. native dependency versions) must keep their local tasks for now. See `CHANGELOG.md`.

## Build from the source

Download and install Java SE Development Kit (JDK) version 17. You can download it from either of the following sources:

  * [Oracle JDK](https://www.oracle.com/java/technologies/downloads/)
  * [OpenJDK](https://adoptium.net/)

   > **Note:** After installation, remember to set the `JAVA_HOME` environment variable to the directory where JDK was installed.

### Build options

Execute the commands below to build from the source.

1. To build the package:

   ```bash
   ./gradlew clean build
   ```

2. To publish to maven local:

   ```bash
   ./gradlew clean build publishToMavenLocal
   ```

### Debug options

To debug the Ballerina gradle plugin against a Ballerina library package build use the following command to build the library:

   ```bash
   ./gradlew clean build -Dorg.gradle.debug=true
   ```

This would start a debug process on port `5005` and the developer can configure the remote debug for the Ballerina 
gradle plugin for this port. 
