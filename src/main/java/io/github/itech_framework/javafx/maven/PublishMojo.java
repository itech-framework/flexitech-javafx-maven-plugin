package io.github.itech_framework.javafx.maven;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Packages a JavaFX application into a native installer or application folder
 * for the operating system that runs Maven, by calling the JDK's
 * {@code jpackage} tool.
 *
 * <p>
 * Supported systems and package types:
 * </p>
 * <ul>
 * <li>Windows: {@code msi} (default), {@code exe}, {@code app-image}</li>
 * <li>macOS: {@code dmg} (default), {@code pkg}, {@code app-image}</li>
 * <li>Linux: {@code deb} (default), {@code rpm}, {@code app-image}</li>
 * </ul>
 *
 * <p>
 * {@code jpackage} cannot cross-compile. A package for a system must be built on
 * that system, for example with one build job per operating system.
 * </p>
 *
 * <p>
 * The goal does not compile the project. It needs the project jar in
 * {@code target}, so run it after {@code package}:
 * </p>
 *
 * <pre>
 * mvn clean package flexitech-javafx:publish
 * </pre>
 *
 * <p>
 * Tools needed on the build machine: WiX Toolset 3.x for {@code msi} and
 * {@code exe}, the Xcode command line tools for {@code dmg} and {@code pkg},
 * {@code dpkg-deb} and {@code fakeroot} for {@code deb}, and {@code rpmbuild}
 * for {@code rpm}. {@code app-image} needs no extra tool.
 * </p>
 *
 * @since 0.0.1
 */
@Mojo(name = "publish", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class PublishMojo extends AbstractMojo {

	private enum Platform {
		WINDOWS, MAC, LINUX
	}

	/**
	 * The Maven project that is being packaged. Injected by Maven.
	 */
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	/**
	 * The application name. It is used for the installer file, the menu entry, the
	 * shortcut and the install folder. Defaults to the project's
	 * {@code artifactId}.
	 */
	@Parameter(property = "fx.name", defaultValue = "${project.artifactId}")
	private String name;

	/**
	 * The installer version, numbers separated by dots such as {@code 1.0.0}. A
	 * {@code -SNAPSHOT} suffix is removed before the check. On macOS the first
	 * number must be 1 or higher. Defaults to the project's version.
	 */
	@Parameter(property = "fx.version", defaultValue = "${project.version}")
	private String version;

	/**
	 * The vendor name shown in the installer. Optional.
	 */
	@Parameter(property = "fx.vendor")
	private String vendor;

	/**
	 * The fully qualified name of the launcher class that starts the application.
	 * Use a small class that only calls the real main class, because a main class
	 * that extends {@code javafx.application.Application} often fails with
	 * "JavaFX runtime components are missing".
	 */
	@Parameter(property = "fx.mainClass", required = true)
	private String mainClass;

	/**
	 * The operating system to build for: {@code auto}, {@code windows},
	 * {@code mac} or {@code linux}. With {@code auto} the system that runs Maven
	 * is used. A value for another system stops the build, because
	 * {@code jpackage} cannot cross-compile.
	 */
	@Parameter(property = "fx.platform", defaultValue = "auto")
	private String platform;

	/**
	 * The package type. Windows: {@code msi}, {@code exe}, {@code app-image}.
	 * macOS: {@code dmg}, {@code pkg}, {@code app-image}. Linux: {@code deb},
	 * {@code rpm}, {@code app-image}. When empty, the first type of the system
	 * is used.
	 */
	@Parameter(property = "fx.type")
	private String type;

	/**
	 * Path to the icon file used on Windows ({@code .ico}). Also used on macOS and
	 * Linux when {@link #macIcon} or {@link #linuxIcon} is not set and the file
	 * extension fits. Optional.
	 */
	@Parameter(property = "fx.icon")
	private File icon;

	/**
	 * Path to the macOS icon file ({@code .icns}). Optional.
	 */
	@Parameter(property = "fx.macIcon")
	private File macIcon;

	/**
	 * Path to the Linux icon file ({@code .png}). Optional.
	 */
	@Parameter(property = "fx.linuxIcon")
	private File linuxIcon;

	/**
	 * The Java modules included in the bundled runtime, separated by commas. Use
	 * {@code ALL-MODULE-PATH} to include every module of the JDK. A missing module
	 * fails only when the feature that needs it is used, so test every feature of
	 * the installed application after changing the list.
	 */
	@Parameter(property = "fx.modules", defaultValue = "java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql,java.xml,jdk.charsets,jdk.compiler,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.localedata,jdk.naming.dns,jdk.unsupported,jdk.zipfs")
	private String modules;

	/**
	 * Windows only. A fixed UUID that lets Windows upgrade an installed version.
	 * Generate it once and never change it.
	 */
	@Parameter(property = "fx.upgradeUuid")
	private String upgradeUuid;

	/**
	 * Windows only. When {@code true}, the installer installs for the current user
	 * only, needs no administrator rights and installs to a writable folder.
	 */
	@Parameter(property = "fx.perUser", defaultValue = "true")
	private boolean perUser;

	/**
	 * macOS only. The bundle identifier in reverse domain form, for example
	 * {@code com.example.planner}. Optional.
	 */
	@Parameter(property = "fx.macIdentifier")
	private String macIdentifier;

	/**
	 * macOS only. The name shown in the menu bar, 16 characters or fewer. Optional.
	 */
	@Parameter(property = "fx.macPackageName")
	private String macPackageName;

	/**
	 * macOS only. Signs the application when {@code true}. Needs an Apple
	 * Developer certificate in the keychain.
	 */
	@Parameter(property = "fx.macSign", defaultValue = "false")
	private boolean macSign;

	/**
	 * macOS only. The name of the signing certificate in the keychain, used when
	 * {@link #macSign} is {@code true}.
	 */
	@Parameter(property = "fx.macSigningKey")
	private String macSigningKey;

	/**
	 * Linux only. The menu group of the application, for example
	 * {@code Office}. Optional.
	 */
	@Parameter(property = "fx.linuxMenuGroup")
	private String linuxMenuGroup;

	/**
	 * Linux only. The maintainer e-mail address of a {@code deb} package.
	 * Optional.
	 */
	@Parameter(property = "fx.linuxMaintainer")
	private String linuxMaintainer;

	/**
	 * Whether the runtime dependency jars are copied next to the project jar. Set
	 * it to {@code false} when the project jar already contains its dependencies,
	 * for example when it is built with the Maven Shade plugin.
	 */
	@Parameter(property = "fx.copyLibs", defaultValue = "true")
	private boolean copyLibs;

	/**
	 * Skips the goal when {@code true}.
	 */
	@Parameter(property = "fx.skip", defaultValue = "false")
	private boolean skip;

	/**
	 * Prepares the input folder and runs {@code jpackage}.
	 *
	 * @throws MojoExecutionException if the system or package type is not
	 *                                supported, the version is not valid, the
	 *                                project jar is missing, {@code jpackage} is
	 *                                not available or it exits with an error
	 */
	@Override
	public void execute() throws MojoExecutionException {
		if (skip) {
			getLog().info("Publish skipped");
			return;
		}
		Platform target = resolvePlatform();
		String packageType = resolveType(target);
		String cleanVersion = checkVersion(target);
		checkJavaFxNatives(target);

		Path buildDir = Path.of(project.getBuild().getDirectory());
		Path dist = buildDir.resolve("dist");
		Path installer = buildDir.resolve("installer");
		Path mainJar = buildDir.resolve(project.getBuild().getFinalName() + ".jar");
		if (!Files.isRegularFile(mainJar)) {
			throw new MojoExecutionException(
					"Jar not found: " + mainJar + ". Run 'mvn clean package flexitech-javafx:publish'.");
		}
		String jarName = mainJar.getFileName().toString();

		try {
			recreate(dist);
			Files.createDirectories(installer);
			Files.copy(mainJar, dist.resolve(jarName), StandardCopyOption.REPLACE_EXISTING);
			int libs = 0;
			if (copyLibs) {
				for (Artifact artifact : project.getArtifacts()) {
					File file = artifact.getFile();
					if (file == null || !file.isFile() || "test".equals(artifact.getScope())
							|| "provided".equals(artifact.getScope())) {
						continue;
					}
					Files.copy(file.toPath(), dist.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
					libs++;
				}
			}
			getLog().info("Collected " + jarName + " and " + libs + " libraries in " + dist);
		} catch (IOException e) {
			throw new MojoExecutionException("Could not prepare the input folder", e);
		}

		List<String> arguments = new ArrayList<String>();
		add(arguments, "--type", packageType);
		add(arguments, "--name", name);
		add(arguments, "--app-version", cleanVersion);
		add(arguments, "--input", dist.toString());
		add(arguments, "--main-jar", jarName);
		add(arguments, "--main-class", mainClass);
		add(arguments, "--dest", installer.toString());
		add(arguments, "--add-modules", modules);
		if (notBlank(vendor)) {
			add(arguments, "--vendor", vendor);
		}
		addIcon(arguments, target);
		boolean image = "app-image".equals(packageType);
		switch (target) {
		case WINDOWS -> addWindowsOptions(arguments, image);
		case MAC -> addMacOptions(arguments);
		case LINUX -> addLinuxOptions(arguments, packageType, image);
		}

		ToolProvider jpackage = ToolProvider.findFirst("jpackage").orElseThrow(
				() -> new MojoExecutionException("jpackage was not found. Run Maven with JDK 17 or newer."));
		getLog().info("Running jpackage " + String.join(" ", arguments));
		int code = jpackage.run(System.out, System.err, arguments.toArray(new String[0]));
		if (code != 0) {
			throw new MojoExecutionException("jpackage failed with exit code " + code + "." + toolHint(packageType));
		}
		getLog().info("Package created in " + installer);
	}

	private Platform resolvePlatform() throws MojoExecutionException {
		Platform current = detectPlatform();
		String wanted = platform == null ? "auto" : platform.trim().toLowerCase(Locale.ROOT);
		if (wanted.isEmpty() || "auto".equals(wanted)) {
			return current;
		}
		Platform requested = switch (wanted) {
		case "windows", "win" -> Platform.WINDOWS;
		case "mac", "macos", "osx" -> Platform.MAC;
		case "linux" -> Platform.LINUX;
		default -> throw new MojoExecutionException(
				"Unknown platform '" + platform + "'. Use auto, windows, mac or linux.");
		};
		if (requested != current) {
			throw new MojoExecutionException("jpackage cannot build for " + requested + " on " + current
					+ ". Run this build on a " + requested + " machine.");
		}
		return requested;
	}

	private static Platform detectPlatform() throws MojoExecutionException {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac") || os.contains("darwin")) {
			return Platform.MAC;
		}
		if (os.contains("win")) {
			return Platform.WINDOWS;
		}
		if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
			return Platform.LINUX;
		}
		throw new MojoExecutionException("Unsupported operating system: " + os);
	}

	private String resolveType(Platform target) throws MojoExecutionException {
		List<String> allowed = switch (target) {
		case WINDOWS -> List.of("msi", "exe", "app-image");
		case MAC -> List.of("dmg", "pkg", "app-image");
		case LINUX -> List.of("deb", "rpm", "app-image");
		};
		String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
		if (value.isEmpty() || "auto".equals(value)) {
			return allowed.get(0);
		}
		if (!allowed.contains(value)) {
			throw new MojoExecutionException(
					"The type '" + type + "' is not available on " + target + ". Use one of " + allowed + ".");
		}
		return value;
	}

	private String checkVersion(Platform target) throws MojoExecutionException {
		String cleanVersion = version.replace("-SNAPSHOT", "");
		if (!cleanVersion.matches("\\d{1,9}(\\.\\d{1,9}){0,2}")) {
			throw new MojoExecutionException("The installer version must be numbers like 1.0.0, but it is '"
					+ cleanVersion + "'. Set <version> in the plugin configuration.");
		}
		if (target == Platform.MAC && Integer.parseInt(cleanVersion.split("\\.")[0]) < 1) {
			throw new MojoExecutionException("On macOS the first number of the version must be 1 or higher, but it is '"
					+ cleanVersion + "'. Set <version> in the plugin configuration, for example 1.0.0.");
		}
		return cleanVersion;
	}

	private void checkJavaFxNatives(Platform target) {
		String prefix = switch (target) {
		case WINDOWS -> "win";
		case MAC -> "mac";
		case LINUX -> "linux";
		};
		for (Artifact artifact : project.getArtifacts()) {
			String classifier = artifact.getClassifier();
			if ("org.openjfx".equals(artifact.getGroupId()) && notBlank(classifier)
					&& !classifier.startsWith(prefix)) {
				getLog().warn("JavaFX jar " + artifact.getFile().getName() + " is built for '" + classifier
						+ "', but you are packaging for " + target
						+ ". The application will not start. Build on the target system with its own JavaFX jars.");
				return;
			}
		}
	}

	private void addIcon(List<String> arguments, Platform target) {
		File chosen = switch (target) {
		case WINDOWS -> icon;
		case MAC -> macIcon != null ? macIcon : icon;
		case LINUX -> linuxIcon != null ? linuxIcon : icon;
		};
		if (chosen == null) {
			return;
		}
		String extension = switch (target) {
		case WINDOWS -> ".ico";
		case MAC -> ".icns";
		case LINUX -> ".png";
		};
		if (!chosen.isFile()) {
			getLog().warn("Icon file not found, the default icon is used: " + chosen);
		} else if (!chosen.getName().toLowerCase(Locale.ROOT).endsWith(extension)) {
			getLog().warn("The icon " + chosen.getName() + " is not a " + extension + " file, so it is skipped for "
					+ target + ".");
		} else {
			add(arguments, "--icon", chosen.getAbsolutePath());
		}
	}

	private void addWindowsOptions(List<String> arguments, boolean image) {
		if (image) {
			return;
		}
		arguments.add("--win-menu");
		arguments.add("--win-shortcut");
		arguments.add("--win-dir-chooser");
		if (perUser) {
			arguments.add("--win-per-user-install");
		}
		if (notBlank(upgradeUuid)) {
			add(arguments, "--win-upgrade-uuid", upgradeUuid);
		}
	}

	private void addMacOptions(List<String> arguments) {
		if (notBlank(macIdentifier)) {
			add(arguments, "--mac-package-identifier", macIdentifier);
		}
		if (notBlank(macPackageName)) {
			add(arguments, "--mac-package-name", macPackageName);
		}
		if (macSign) {
			arguments.add("--mac-sign");
			if (notBlank(macSigningKey)) {
				add(arguments, "--mac-signing-key-user-name", macSigningKey);
			}
		}
	}

	private void addLinuxOptions(List<String> arguments, String packageType, boolean image) {
		if (image) {
			return;
		}
		arguments.add("--linux-shortcut");
		add(arguments, "--linux-package-name", name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.+-]", "-"));
		if (notBlank(linuxMenuGroup)) {
			add(arguments, "--linux-menu-group", linuxMenuGroup);
		}
		if ("deb".equals(packageType) && notBlank(linuxMaintainer)) {
			add(arguments, "--linux-deb-maintainer", linuxMaintainer);
		}
	}

	private static String toolHint(String packageType) {
		return switch (packageType) {
		case "msi", "exe" -> " For msi and exe, the WiX Toolset 3.x must be installed.";
		case "dmg", "pkg" -> " For dmg and pkg, the Xcode command line tools must be installed.";
		case "deb" -> " For deb, dpkg-deb and fakeroot must be installed.";
		case "rpm" -> " For rpm, rpmbuild must be installed.";
		default -> "";
		};
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

	private static void add(List<String> arguments, String option, String value) {
		arguments.add(option);
		arguments.add(value);
	}

	private static void recreate(Path folder) throws IOException {
		if (Files.exists(folder)) {
			try (Stream<Path> paths = Files.walk(folder)) {
				paths.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.delete(path);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});
			}
		}
		Files.createDirectories(folder);
	}
}