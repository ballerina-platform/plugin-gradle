# Changelog
This file documents all notable changes to the Ballerina Gradle plugin.

## Unreleased

### Breaking change: `updateTomlFiles` and `commitTomlFiles` are now provided by the plugin

The plugin now registers `updateTomlFiles` and `commitTomlFiles` tasks itself (previously every consumer had to hand-roll them, and all of those hand-rolled copies used `project.exec`, which Gradle 9.0 removed).

**Consumers must delete their local `task updateTomlFiles { ... }` and `task commitTomlFiles { ... }` declarations (and any related `ExecOperations`-injection helper class such as `BallerinaExecHelper`) before upgrading to this version.** Leaving them in place will fail the build with a duplicate-task-name error.

The plugin's `updateTomlFiles` templates `build-config/resources/Ballerina.toml` (substituting `@project.version@` and `@toml.version@`) into `Ballerina.toml`, and — only if `build-config/resources/CompilerPlugin.toml` exists — templates it (substituting `@project.version@`) into `CompilerPlugin.toml`. `commitTomlFiles` commits `Ballerina.toml`, `Dependencies.toml`, and `CompilerPlugin.toml` (if present) with the message `[Automated] Update the toml files`.

**This migration is only safe for consumers whose `Ballerina.toml`/`CompilerPlugin.toml` templates use no placeholders beyond `@project.version@`/`@toml.version@`.** Repos with additional module-specific placeholders (as of this writing: `module-ballerina-os`, `module-ballerina-http`, `module-ballerina-data.jsondata`, which substitute extra native-dependency version tokens) must keep their local tasks until a follow-up change adds a way to parameterize extra placeholders — do not migrate these yet.
