# Flexitech JavaFX Maven Plugin

A Maven plugin that packages a JavaFX application into a native installer or a ready-to-run application folder for Windows, macOS and Linux. It runs the JDK's `jpackage` tool, so the people who install your app do not need to install Java.

- Plugin: `io.github.itech-framework:flexitech-javafx-maven-plugin`
- Goal prefix: `flexitech-javafx`
- Goal: `publish`

## Status

| System | Status |
|---|---|
| Windows | Tested (`msi` and `app-image`) |
| macOS | Implemented, not tested yet |
| Linux | Implemented, not tested yet |
| iOS and Android | Not supported. `jpackage` cannot build mobile apps. |

If a macOS or Linux build reports an invalid option, please report the exact message.

## Contents

1. [Important: build on each system](#important-build-on-each-system)
2. [Requirements](#requirements)
3. [Install the plugin](#install-the-plugin)
4. [Prepare your application](#prepare-your-application)
5. [Configure the plugin](#configure-the-plugin)
6. [Build a package](#build-a-package)
7. [Parameters](#parameters)
8. [Platform notes](#platform-notes)
9. [Java modules](#java-modules)
10. [Run from Eclipse](#run-from-eclipse)
11. [What the goal does](#what-the-goal-does)
12. [Troubleshooting](#troubleshooting)
13. [Release checklist](#release-checklist)
14. [Limits](#limits)

## Important: build on each system

`jpackage` cannot cross-compile. A package for a system must be built on that system:

| Target | Build on |
|---|---|
| Windows | A Windows machine |
| macOS | A Mac |
| Linux | A Linux machine |

If you ask for a different system than the one that runs Maven, the build stops with a message instead of failing inside `jpackage`.

The whole application build must also run on the target system. A jar that was shaded on Windows contains Windows JavaFX native libraries, so you cannot copy it to a Mac and package it there.

## Requirements

| Requirement | Details |
|---|---|
| JDK | 17 or newer, running Maven. `jpackage` must come from that JDK. |
| Maven | 3.9 or newer. |
| Application | A JavaFX application packaged as one jar. |

Extra tools, by package type:

| Package type | Tool needed on the build machine |
|---|---|
| `msi`, `exe` | WiX Toolset 3.x, with its `bin` folder on the PATH |
| `dmg`, `pkg` | Xcode command line tools (`xcode-select --install`) |
| `deb` | `dpkg-deb` and `fakeroot` |
| `rpm` | `rpmbuild` (the `rpm-build` package) |
| `app-image` | Nothing extra, on every system |

Check the JDK with:

```
java -version
jpackage --version
```

Both must show version 17 or higher.

## Install the plugin

Build and install it into your local Maven repository once:

```
mvn clean install
```

Applications on the same machine can then use it. To share it with others, publish it to a repository they can reach.

## Prepare your application

### 1. A launcher class

A main class that extends `javafx.application.Application` often fails with `JavaFX runtime components are missing` when started from the classpath. Use a small launcher class that only starts your real main class:

```java
package org.example;

public final class AppLauncher {

	private AppLauncher() {
	}

	public static void main(String[] args) {
		MyApplication.main(args);
	}
}
```

`MyApplication.main` must call `Application.launch`.

### 2. One jar with all dependencies

The plugin takes the project jar from `target`. The simplest setup is a single jar that already contains every dependency, built with the Maven Shade plugin and `AppLauncher` as the main class:

```xml
<plugin>
	<groupId>org.apache.maven.plugins</groupId>
	<artifactId>maven-shade-plugin</artifactId>
	<version>3.5.3</version>
	<executions>
		<execution>
			<phase>package</phase>
			<goals>
				<goal>shade</goal>
			</goals>
			<configuration>
				<createDependencyReducedPom>false</createDependencyReducedPom>
				<shadedArtifactAttached>false</shadedArtifactAttached>
				<filters>
					<filter>
						<artifact>*:*</artifact>
						<excludes>
							<exclude>META-INF/*.SF</exclude>
							<exclude>META-INF/*.DSA</exclude>
							<exclude>META-INF/*.RSA</exclude>
							<exclude>module-info.class</exclude>
						</excludes>
					</filter>
				</filters>
				<transformers>
					<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer" />
					<transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
						<mainClass>org.example.AppLauncher</mainClass>
					</transformer>
				</transformers>
			</configuration>
		</execution>
	</executions>
</plugin>
```

If your application uses Log4j 2 plugins, also add the Log4j 2 plugin cache transformer to the shade configuration.

If you do not shade, set `copyLibs` to `true` (the default). The plugin then copies the dependency jars next to the project jar.

### 3. JavaFX jars for each system

JavaFX contains native libraries, and Maven normally picks the jars that match the system that runs the build. Build on the target system so that the jars match. If a JavaFX jar for another system ends up on the classpath, the plugin prints a warning.

## Configure the plugin

Add the plugin to the `<build><plugins>` section of your application's `pom.xml`. This one configuration works on all three systems:

```xml
<plugin>
	<groupId>io.github.itech-framework</groupId>
	<artifactId>flexitech-javafx-maven-plugin</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<configuration>
		<name>Planner</name>
		<vendor>Flexitech</vendor>
		<mainClass>org.example.AppLauncher</mainClass>
		<version>1.0.0</version>
		<upgradeUuid>3f9c1a52-7d4e-4b8a-9e61-2c5d8f0a1b37</upgradeUuid>
		<icon>logo/logo.ico</icon>
		<macIcon>logo/logo.icns</macIcon>
		<linuxIcon>logo/logo.png</linuxIcon>
		<copyLibs>false</copyLibs>
	</configuration>
</plugin>
```

Options that belong to another system are ignored, so the same pom can be used everywhere.

Generate your own `upgradeUuid`, for example with an online UUID generator, and keep it the same for the whole life of the application.

## Build a package

The goal does not compile the project. Run it after `package`, on the same command line:

```
mvn clean package flexitech-javafx:publish
```

The result is created in `target/installer`. The default type depends on the system:

| System | Default type | Result |
|---|---|---|
| Windows | `msi` | `Planner-1.0.0.msi` |
| macOS | `dmg` | `Planner-1.0.0.dmg` |
| Linux | `deb` | `planner_1.0.0-1_amd64.deb` |

For a quick test without installer tools, build a plain application folder:

```
mvn clean package flexitech-javafx:publish -Dfx.type=app-image
```

| System | Result |
|---|---|
| Windows | `target/installer/Planner/Planner.exe` |
| macOS | `target/installer/Planner.app` |
| Linux | `target/installer/Planner/bin/Planner` |

Any parameter can be set on the command line with its property name:

```
mvn clean package flexitech-javafx:publish -Dfx.version=1.2.0 -Dfx.type=pkg
```

## Parameters

### General

| Parameter | Property | Default | Required | Description |
|---|---|---|---|---|
| `name` | `fx.name` | `${project.artifactId}` | No | Application name. Used for the package file, the menu entry, the shortcut and the install folder. |
| `version` | `fx.version` | `${project.version}` | No | Package version. Numbers separated by dots only, such as `1.0.0`. A `-SNAPSHOT` suffix is removed. Any other text suffix makes the build fail. On macOS the first number must be 1 or higher. |
| `vendor` | `fx.vendor` | none | No | Vendor name shown in the installer. |
| `mainClass` | `fx.mainClass` | none | **Yes** | Fully qualified name of the launcher class. |
| `platform` | `fx.platform` | `auto` | No | `auto`, `windows`, `mac` or `linux`. With `auto` the system that runs Maven is used. A different system stops the build. |
| `type` | `fx.type` | first type of the system | No | `msi`, `exe`, `dmg`, `pkg`, `deb`, `rpm` or `app-image`. A type that does not exist on the system is rejected. |
| `modules` | `fx.modules` | see [Java modules](#java-modules) | No | Modules included in the bundled runtime, separated by commas. `ALL-MODULE-PATH` includes every JDK module. |
| `copyLibs` | `fx.copyLibs` | `true` | No | Copy the runtime dependency jars next to the project jar. Set it to `false` when the jar already contains its dependencies. |
| `skip` | `fx.skip` | `false` | No | Skip the goal. |

### Icons

| Parameter | Property | File type | Used on |
|---|---|---|---|
| `icon` | `fx.icon` | `.ico` | Windows. Also a fallback on macOS and Linux if the file type fits. |
| `macIcon` | `fx.macIcon` | `.icns` | macOS |
| `linuxIcon` | `fx.linuxIcon` | `.png` | Linux |

A file that does not exist or has the wrong extension is skipped with a warning, and the default icon is used.

### Windows only

| Parameter | Property | Default | Description |
|---|---|---|---|
| `upgradeUuid` | `fx.upgradeUuid` | none | Fixed UUID that lets Windows upgrade an installed version. Never change it after the first release. |
| `perUser` | `fx.perUser` | `true` | Install for the current user only. No administrator rights are needed and the install folder is writable. When `false`, the app goes to `Program Files`, which a normal user cannot write to. |

For `msi` and `exe`, the plugin also adds a Start Menu entry, a desktop shortcut and a folder chooser.

### macOS only

| Parameter | Property | Default | Description |
|---|---|---|---|
| `macIdentifier` | `fx.macIdentifier` | none | Bundle identifier in reverse domain form, such as `com.example.planner`. |
| `macPackageName` | `fx.macPackageName` | none | Name shown in the menu bar, 16 characters or fewer. |
| `macSign` | `fx.macSign` | `false` | Sign the application. Needs an Apple Developer certificate in the keychain. |
| `macSigningKey` | `fx.macSigningKey` | none | Name of the signing certificate, used when `macSign` is `true`. |

### Linux only

| Parameter | Property | Default | Description |
|---|---|---|---|
| `linuxMenuGroup` | `fx.linuxMenuGroup` | none | Menu group of the application, for example `Office`. |
| `linuxMaintainer` | `fx.linuxMaintainer` | none | Maintainer e-mail address of a `deb` package. |

For `deb` and `rpm`, the plugin also adds a shortcut and a lowercase package name made from `name`.

## Platform notes

### Windows

- `msi` and `exe` need WiX 3.x. WiX 4 and newer do not work with the `jpackage` of JDK 17.
- With `perUser` set to `true` the application installs under the user's profile, so logs and settings can be written next to the application. For an install under `Program Files`, write logs and settings to `%APPDATA%`.
- An unsigned installer shows a SmartScreen warning. A code-signing certificate removes it.

### macOS

- The version must start with 1 or higher. A project version such as `0.0.1-SNAPSHOT` fails, so set `<version>1.0.0</version>` in the plugin configuration.
- An unsigned application is blocked by Gatekeeper, and most users cannot open it without changing security settings. A public release needs an Apple Developer account for signing and notarization. The plugin passes `--mac-sign` when `macSign` is `true`, but it does not notarize.
- Apple Silicon and Intel Macs need different JavaFX native libraries. Build on the type of Mac you want to support.
- The tray icon appears in the menu bar and behaves differently from the Windows tray.

### Linux

- A package is built for the architecture of the build machine.
- Some desktop environments do not support the system tray. Make sure your application has a fallback, such as an in-app message.
- `rpm` needs `rpmbuild`, and `deb` needs `dpkg-deb` and `fakeroot`.

## Java modules

`jpackage` builds a trimmed Java runtime that contains only the modules you list. A module that is missing does not fail the build. It fails later, with `NoClassDefFoundError`, when a user reaches the feature that needs it.

The default list is:

```
java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql,java.xml,jdk.charsets,jdk.compiler,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.localedata,jdk.naming.dns,jdk.unsupported,jdk.zipfs
```

It worked on Windows for a JavaFX application that uses HTTPS calls, a local login callback server, a system tray icon and Log4j 2. Your application may need a different list.

### Common modules

| Module | Needed for |
|---|---|
| `java.desktop` | JavaFX, AWT, the system tray |
| `java.logging`, `java.management` | Log4j 2 and JavaFX |
| `java.xml`, `java.scripting` | FXML loading |
| `java.prefs` | The Preferences API |
| `java.sql` | Libraries that reference `java.sql` types |
| `java.net.http`, `jdk.crypto.ec` | HTTPS, including modern TLS certificates |
| `jdk.httpserver` | `com.sun.net.httpserver`, for example a local OAuth callback |
| `jdk.unsupported` | Libraries that use `sun.misc.Unsafe` |
| `jdk.localedata` | Dates and names in non-English locales |
| `jdk.charsets` | Text in non-UTF-8 encodings |
| `jdk.zipfs` | Reading files inside jars through the file-system API |

### Choosing a list

| Situation | Recommendation |
|---|---|
| First build, or the application does not start | Set `<modules>ALL-MODULE-PATH</modules>`. The package is larger, but no module can be missing. |
| Release | Use an explicit list and test every feature on a clean machine. |
| You cannot test every feature | Keep `ALL-MODULE-PATH`. |

To get a starting list, run `jdeps` against your jar:

```
jdeps --print-module-deps --ignore-missing-deps --multi-release 17 target/your-app.jar
```

`jdeps` cannot see classes loaded by reflection, so add the modules your frameworks use that way, for example `jdk.httpserver`, `jdk.unsupported`, `jdk.crypto.ec` and `java.net.http`.

### Find a missing module

An installed application has no console. To see the real error, run the bundled Java by hand.

Windows, per-user install:

```
"%LOCALAPPDATA%\Planner\runtime\bin\java.exe" -cp "%LOCALAPPDATA%\Planner\app\*" org.example.AppLauncher
```

macOS:

```
/Applications/Planner.app/Contents/runtime/Contents/Home/bin/java -cp "/Applications/Planner.app/Contents/app/*" org.example.AppLauncher
```

Linux:

```
/opt/planner/lib/runtime/bin/java -cp "/opt/planner/lib/app/*" org.example.AppLauncher
```

The macOS and Linux paths are the usual locations and may differ on your machine. Read the exception. A `NoClassDefFoundError` names a class, and the package tells you which module to add.

## Run from Eclipse

Create a Maven run configuration (Run, Run Configurations, Maven Build):

| Field | Value |
|---|---|
| Base directory | `${workspace_loc:/your-project}` |
| Goals | `clean package flexitech-javafx:publish` |
| Parameter name and value | `fx.type` and `app-image` (remove the row for the default installer type) |
| JRE tab, Runtime JRE | Separate JRE, a JDK 17 |
| Environment tab | `JAVA_HOME` set to the same JDK 17 folder (optional safeguard) |

The `EMBEDDED` Maven runtime works as long as the JRE tab points to a JDK that contains `jpackage`. If the build reports `jpackage was not found`, check that tab first.

After you change and reinstall the plugin, run `mvn clean install` in the plugin project, and then run the application build again.

## What the goal does

1. Stops if `skip` is `true`.
2. Finds the system that runs Maven and checks it against `platform`.
3. Chooses the package type and checks that it exists on that system.
4. Removes `-SNAPSHOT` from the version and checks that it is numeric, and that it starts with 1 or higher on macOS.
5. Warns if a JavaFX jar for another system is on the classpath.
6. Finds the project jar in `target` (`<finalName>.jar`). It fails if the jar does not exist, so run `package` first.
7. Recreates `target/dist` and copies the project jar into it, plus the dependency jars when `copyLibs` is `true`.
8. Chooses the icon for the system and skips it with a warning if it is missing or has the wrong extension.
9. Adds the options of the system (Windows, macOS or Linux) and runs `jpackage` with `target/dist` as input and `target/installer` as output.

## Troubleshooting

| Message or symptom | Cause and fix |
|---|---|
| `jpackage was not found. Run Maven with JDK 17 or newer.` | Maven runs on a JRE or an old JDK. Use a JDK 17 or newer, and in Eclipse set it on the JRE tab. |
| `jpackage cannot build for MAC on WINDOWS` | You asked for another system than the one running Maven. Run the build on that system, or remove `platform`. |
| `The type 'dmg' is not available on WINDOWS` | The type belongs to another system. Use one of the types in the message. |
| `The installer version must be numbers like 1.0.0` | The version has a text suffix. Set `<version>` in the plugin configuration or pass `-Dfx.version=1.0.0`. |
| `On macOS the first number of the version must be 1 or higher` | The version starts with 0, such as `0.0.1`. Set `<version>1.0.0</version>`. |
| `Jar not found: ... Run 'mvn clean package ...'` | The goal ran without `package`. Put `package` before it on the command line. |
| `jpackage failed ... WiX Toolset 3.x must be installed` | Install WiX 3.x and put its `bin` folder on the PATH. For a test without WiX, use `-Dfx.type=app-image`. |
| `jpackage failed ... Xcode command line tools` | Run `xcode-select --install` on the Mac. |
| `jpackage failed ... dpkg-deb and fakeroot` or `rpmbuild` | Install the tool with the package manager of your Linux distribution. |
| Warning: `JavaFX jar ... is built for 'win', but you are packaging for MAC` | The JavaFX native libraries do not match the build system. Build the whole project on the target system. |
| Warning: `The icon ... is not a .icns file, so it is skipped` | Set `macIcon` to an `.icns` file, `linuxIcon` to a `.png` file and `icon` to an `.ico` file. |
| The application does not start, with no message | A module is missing, or the main class is wrong. Run the bundled Java by hand as shown in [Find a missing module](#find-a-missing-module). To test quickly, set `<modules>ALL-MODULE-PATH</modules>`. |
| `JavaFX runtime components are missing` | The main class extends `Application`. Use a separate launcher class. |
| Starts, but sign-in or HTTPS calls fail | `jdk.crypto.ec` or `java.net.http` is missing from the module list. |
| Saved settings or tokens are not kept, or files cannot be written | On Windows, the application is installed under `Program Files`. Keep `perUser` set to `true`, and write logs and settings to the user's profile. |
| macOS says the application is damaged or from an unidentified developer | The application is not signed or notarized. Sign it with an Apple Developer certificate, or right-click and choose Open for a local test. |
| Components or classes are not found only in the installed app | Check that your framework's class scanning works inside a jar. |
| The package is much larger than expected | `copyLibs` is `true` for a jar that already contains its dependencies, or `ALL-MODULE-PATH` is used. |
| A new Windows version installs next to the old one | `upgradeUuid` changed. Use the original UUID, and raise `version`. |

## Release checklist

1. Raise the application `version`. On Windows, keep the same `upgradeUuid`.
2. Build on each target system with `mvn clean package flexitech-javafx:publish`.
3. Install each result on a clean machine or virtual machine that has no Java.
4. Test every feature that touches the JDK: sign-in, network calls, file access, the tray, opening links in the browser, and a restart with saved data.
5. Check the logs for `NoClassDefFoundError` and `ClassNotFoundException`.
6. Install the new version over the old one and check that it upgrades.
7. Sign the packages: a code-signing certificate on Windows, and an Apple Developer certificate with notarization on macOS. Without signing, Windows shows a SmartScreen warning and macOS blocks the application.

## Limits

- Each system must be built on that system. The plugin does not cross-compile.
- Windows is the only system that has been tested so far.
- There is no automatic update mechanism. Users install a new version over the old one.
- The plugin passes signing options to `jpackage` on macOS but does not sign Windows installers or notarize macOS applications.
- The module list is not worked out automatically. It must be maintained by hand.

## License

Add the license of your choice here.