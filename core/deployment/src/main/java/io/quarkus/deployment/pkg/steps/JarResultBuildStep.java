package io.quarkus.deployment.pkg.steps;

import static io.quarkus.bootstrap.util.ZipUtils.wrapForJDK8232879;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.lang3.SystemUtils;
import org.jboss.logging.Logger;

import io.quarkus.bootstrap.BootstrapDependencyProcessingException;
import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.model.PersistentAppModel;
import io.quarkus.bootstrap.resolver.AppModelResolverException;
import io.quarkus.bootstrap.runner.QuarkusEntryPoint;
import io.quarkus.bootstrap.runner.SerializedApplication;
import io.quarkus.bootstrap.util.IoUtils;
import io.quarkus.bootstrap.util.ZipUtils;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalApplicationArchiveBuildItem;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.GeneratedNativeImageClassBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.MainClassBuildItem;
import io.quarkus.deployment.builditem.QuarkusBuildCloseablesBuildItem;
import io.quarkus.deployment.builditem.TransformedClassesBuildItem;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.AppCDSRequestedBuildItem;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.BuildSystemTargetBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.JarBuildItem;
import io.quarkus.deployment.pkg.builditem.LegacyJarRequiredBuildItem;
import io.quarkus.deployment.pkg.builditem.NativeImageSourceJarBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.deployment.pkg.builditem.UberJarRequiredBuildItem;

/**
 * This build step builds both the thin jars and uber jars.
 *
 * The way this is built is a bit convoluted. In general we only want a single one built,
 * as determined by the {@link PackageConfig} (unless the config explicitly asks for both of them)
 *
 * However we still need an extension to be able to ask for a specify one of these despite the config,
 * e.g. if a serverless environment needs an uberjar to build its deployment package then we need
 * to be able to provide this.
 *
 * To enable this we have two build steps that strongly produce the respective artifact type build
 * items, but not a {@link ArtifactResultBuildItem}. We then
 * have another two build steps that only run if they are configured too that consume these explicit
 * build items and transform them into {@link ArtifactResultBuildItem}.
 */
public class JarResultBuildStep {

    private static final Collection<String> IGNORED_ENTRIES = Arrays.asList(
            "META-INF/INDEX.LIST",
            "META-INF/MANIFEST.MF",
            "module-info.class",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/NOTICE.md",
            "META-INF/README",
            "META-INF/README.txt",
            "META-INF/README.md",
            "META-INF/DEPENDENCIES",
            "META-INF/DEPENDENCIES.txt",
            "META-INF/beans.xml",
            "META-INF/io.netty.versions.properties",
            "META-INF/quarkus-config-roots.list",
            "META-INF/quarkus-javadoc.properties",
            "META-INF/quarkus-extension.properties",
            "META-INF/quarkus-extension.json",
            "META-INF/quarkus-extension.yaml",
            "META-INF/quarkus-deployment-dependency.graph",
            "META-INF/jandex.idx",
            "META-INF/panache-archive.marker",
            "META-INF/build.metadata", // present in the Red Hat Build of Quarkus
            "LICENSE");

    private static final Logger log = Logger.getLogger(JarResultBuildStep.class);
    // we shouldn't have to specify these flags when opening a ZipFS (since they are the default ones), but failure to do so
    // makes a subsequent uberJar creation fail in java 8 (but works fine in Java 11)
    private static final StandardOpenOption[] DEFAULT_OPEN_OPTIONS = { TRUNCATE_EXISTING, WRITE, CREATE };
    private static final BiPredicate<Path, BasicFileAttributes> IS_JSON_FILE_PREDICATE = new IsJsonFilePredicate();
    public static final String DEPLOYMENT_CLASS_PATH_DAT = "deployment-class-path.dat";
    public static final String BUILD_SYSTEM_PROPERTIES = "build-system.properties";
    public static final String DEPLOYMENT_LIB = "deployment";
    public static final String APPMODEL_DAT = "appmodel.dat";
    public static final String QUARKUS_RUN_JAR = "quarkus-run.jar";
    public static final String BOOT_LIB = "boot";
    public static final String LIB = "lib";
    public static final String MAIN = "main";
    public static final String GENERATED_BYTECODE_JAR = "generated-bytecode.jar";
    public static final String TRANSFORMED_BYTECODE_JAR = "transformed-bytecode.jar";
    public static final String APP = "app";
    public static final String QUARKUS = "quarkus";
    public static final String DEFAULT_FAST_JAR_DIRECTORY_NAME = "quarkus-app";
    public static final String MP_CONFIG_FILE = "META-INF/microprofile-config.properties";

    @BuildStep
    OutputTargetBuildItem outputTarget(BuildSystemTargetBuildItem bst, PackageConfig packageConfig) {
        String name = packageConfig.outputName.orElseGet(bst::getBaseName);
        Path path = packageConfig.outputDirectory.map(s -> bst.getOutputDirectory().resolve(s))
                .orElseGet(bst::getOutputDirectory);
        return new OutputTargetBuildItem(path, name, bst.isRebuild(), bst.getBuildSystemProps());
    }

    @BuildStep(onlyIf = JarRequired.class)
    ArtifactResultBuildItem jarOutput(JarBuildItem jarBuildItem) {
        if (jarBuildItem.getLibraryDir() != null) {
            return new ArtifactResultBuildItem(jarBuildItem.getPath(), PackageConfig.JAR,
                    Collections.singletonMap("library-dir", jarBuildItem.getLibraryDir()));
        } else {
            return new ArtifactResultBuildItem(jarBuildItem.getPath(), PackageConfig.JAR, Collections.emptyMap());
        }
    }

    @BuildStep
    public JarBuildItem buildRunnerJar(CurateOutcomeBuildItem curateOutcomeBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            TransformedClassesBuildItem transformedClasses,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            ApplicationInfoBuildItem applicationInfo,
            PackageConfig packageConfig,
            List<GeneratedClassBuildItem> generatedClasses,
            List<GeneratedResourceBuildItem> generatedResources,
            List<UberJarRequiredBuildItem> uberJarRequired,
            List<LegacyJarRequiredBuildItem> legacyJarRequired,
            QuarkusBuildCloseablesBuildItem closeablesBuildItem,
            List<AdditionalApplicationArchiveBuildItem> additionalApplicationArchiveBuildItems,
            MainClassBuildItem mainClassBuildItem, Optional<AppCDSRequestedBuildItem> appCDS) throws Exception {

        if (appCDS.isPresent()) {
            handleAppCDSSupportFileGeneration(transformedClasses, generatedClasses, appCDS.get());
        }
        if (!(packageConfig.type.equalsIgnoreCase(PackageConfig.JAR) ||
                packageConfig.type.equalsIgnoreCase(PackageConfig.UBER_JAR))
                && packageConfig.uberJar) {
            throw new RuntimeException(
                    "Cannot set quarkus.package.uber-jar=true and quarkus.package.type, if you want an uber-jar set quarkus.package.type=uber-jar.");
        }

        if (!uberJarRequired.isEmpty() && !legacyJarRequired.isEmpty()) {
            throw new RuntimeException(
                    "Extensions with conflicting package types. One extension requires uber-jar another requires legacy format");
        }

        if (legacyJarRequired.isEmpty() && (!uberJarRequired.isEmpty() || packageConfig.uberJar
                || packageConfig.type.equalsIgnoreCase(PackageConfig.UBER_JAR))) {
            return buildUberJar(curateOutcomeBuildItem, outputTargetBuildItem, transformedClasses, applicationArchivesBuildItem,
                    packageConfig, applicationInfo, generatedClasses, generatedResources, closeablesBuildItem,
                    mainClassBuildItem);
        } else if (!legacyJarRequired.isEmpty() || packageConfig.isLegacyJar()
                || packageConfig.type.equalsIgnoreCase(PackageConfig.LEGACY)) {
            return buildLegacyThinJar(curateOutcomeBuildItem, outputTargetBuildItem, transformedClasses,
                    applicationArchivesBuildItem,
                    packageConfig, applicationInfo, generatedClasses, generatedResources, mainClassBuildItem);
        } else {
            return buildThinJar(curateOutcomeBuildItem, outputTargetBuildItem, transformedClasses, applicationArchivesBuildItem,
                    packageConfig, applicationInfo, generatedClasses, generatedResources,
                    additionalApplicationArchiveBuildItems, mainClassBuildItem);
        }
    }

    // the idea here is to just dump the class names of the generated and transformed classes into a file
    // that is read at runtime when AppCDS generation is requested
    private void handleAppCDSSupportFileGeneration(TransformedClassesBuildItem transformedClasses,
            List<GeneratedClassBuildItem> generatedClasses, AppCDSRequestedBuildItem appCDS) throws IOException {
        Path appCDsDir = appCDS.getAppCDSDir();
        Path generatedClassesFile = appCDsDir.resolve("generatedAndTransformed.lst");
        try (BufferedWriter writer = Files.newBufferedWriter(generatedClassesFile, StandardOpenOption.CREATE)) {
            StringBuilder classes = new StringBuilder();
            for (GeneratedClassBuildItem generatedClass : generatedClasses) {
                classes.append(generatedClass.getName().replace('/', '.')).append(System.lineSeparator());
            }

            for (Set<TransformedClassesBuildItem.TransformedClass> transformedClassesSet : transformedClasses
                    .getTransformedClassesByJar().values()) {
                for (TransformedClassesBuildItem.TransformedClass transformedClass : transformedClassesSet) {
                    classes.append(transformedClass.getFileName().replace('/', '.').replace(".class", ""))
                            .append(System.lineSeparator());
                }
            }

            if (classes.length() != 0) {
                writer.write(classes.toString());
            }
        }
    }

    private JarBuildItem buildUberJar(CurateOutcomeBuildItem curateOutcomeBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            TransformedClassesBuildItem transformedClasses,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            PackageConfig packageConfig,
            ApplicationInfoBuildItem applicationInfo,
            List<GeneratedClassBuildItem> generatedClasses,
            List<GeneratedResourceBuildItem> generatedResources,
            QuarkusBuildCloseablesBuildItem closeablesBuildItem,
            MainClassBuildItem mainClassBuildItem) throws Exception {

        //we use the -runner jar name, unless we are building both types
        Path runnerJar = outputTargetBuildItem.getOutputDirectory()
                .resolve(outputTargetBuildItem.getBaseName() + packageConfig.runnerSuffix + ".jar");
        Files.deleteIfExists(runnerJar);

        buildUberJar0(curateOutcomeBuildItem,
                transformedClasses,
                applicationArchivesBuildItem,
                packageConfig,
                applicationInfo,
                generatedClasses,
                generatedResources,
                mainClassBuildItem,
                runnerJar);

        //for uberjars we move the original jar, so there is only a single jar in the output directory
        final Path standardJar = outputTargetBuildItem.getOutputDirectory()
                .resolve(outputTargetBuildItem.getBaseName() + ".jar");

        final Path originalJar = Files.exists(standardJar) ? standardJar : null;

        return new JarBuildItem(runnerJar, originalJar, null, PackageConfig.UBER_JAR,
                suffixToClassifier(packageConfig.runnerSuffix));
    }

    private String suffixToClassifier(String suffix) {
        return suffix.startsWith("-") ? suffix.substring(1) : suffix;
    }

    private void buildUberJar0(CurateOutcomeBuildItem curateOutcomeBuildItem,
            TransformedClassesBuildItem transformedClasses,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            PackageConfig packageConfig,
            ApplicationInfoBuildItem applicationInfo,
            List<GeneratedClassBuildItem> generatedClasses,
            List<GeneratedResourceBuildItem> generatedResources,
            MainClassBuildItem mainClassBuildItem,
            Path runnerJar) throws Exception {
        try (FileSystem runnerZipFs = ZipUtils.newZip(runnerJar)) {

            log.info("Building fat jar: " + runnerJar);

            final Map<String, String> seen = new HashMap<>();
            final Map<String, Set<AppDependency>> duplicateCatcher = new HashMap<>();
            final Map<String, List<byte[]>> services = new HashMap<>();
            Set<String> finalIgnoredEntries = new HashSet<>(IGNORED_ENTRIES);
            packageConfig.userConfiguredIgnoredEntries.ifPresent(finalIgnoredEntries::addAll);

            final List<AppDependency> appDeps = curateOutcomeBuildItem.getEffectiveModel().getUserDependencies();

            AppArtifact appArtifact = curateOutcomeBuildItem.getEffectiveModel().getAppArtifact();
            // the manifest needs to be the first entry in the jar, otherwise JarInputStream does not work properly
            // see https://bugs.openjdk.java.net/browse/JDK-8031748
            generateManifest(runnerZipFs, "", packageConfig, appArtifact, mainClassBuildItem.getClassName(),
                    applicationInfo);

            for (AppDependency appDep : appDeps) {
                final AppArtifact depArtifact = appDep.getArtifact();

                // Exclude files that are not jars (typically, we can have XML files here, see https://github.com/quarkusio/quarkus/issues/2852)
                if (!isAppDepAJar(depArtifact)) {
                    continue;
                }

                for (Path resolvedDep : depArtifact.getPaths()) {
                    Set<String> transformedFromThisArchive = transformedClasses.getTransformedFilesByJar().get(resolvedDep);

                    if (!Files.isDirectory(resolvedDep)) {
                        try (FileSystem artifactFs = ZipUtils.newFileSystem(resolvedDep)) {
                            for (final Path root : artifactFs.getRootDirectories()) {
                                walkFileDependencyForDependency(root, runnerZipFs, seen, duplicateCatcher, services,
                                        finalIgnoredEntries, appDep, transformedFromThisArchive);
                            }
                        }
                    } else {
                        walkFileDependencyForDependency(resolvedDep, runnerZipFs, seen, duplicateCatcher,
                                services, finalIgnoredEntries, appDep, transformedFromThisArchive);
                    }
                }
            }
            Set<Set<AppDependency>> explained = new HashSet<>();
            for (Map.Entry<String, Set<AppDependency>> entry : duplicateCatcher.entrySet()) {
                if (entry.getValue().size() > 1) {
                    if (explained.add(entry.getValue())) {
                        log.warn("Dependencies with duplicate files detected. The dependencies " + entry.getValue()
                                + " contain duplicate files, e.g. " + entry.getKey());
                    }
                }
            }
            copyCommonContent(runnerZipFs, services, applicationArchivesBuildItem, transformedClasses, generatedClasses,
                    generatedResources, seen, finalIgnoredEntries);
        }

        runnerJar.toFile().setReadable(true, false);
    }

    private boolean isAppDepAJar(AppArtifact artifact) {
        return "jar".equals(artifact.getType());
    }

    private void walkFileDependencyForDependency(Path root, FileSystem runnerZipFs, Map<String, String> seen,
            Map<String, Set<AppDependency>> duplicateCatcher, Map<String, List<byte[]>> services,
            Set<String> finalIgnoredEntries, AppDependency appDep, Set<String> transformedFromThisArchive) throws IOException {
        final Path metaInfDir = root.resolve("META-INF");
        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        final String relativePath = toUri(root.relativize(dir));
                        if (!relativePath.isEmpty()) {
                            addDir(runnerZipFs, relativePath);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        final String relativePath = toUri(root.relativize(file));
                        //if this has been transfomed we do not copy it
                        // if it's a signature file (under the <jar>/META-INF directory),
                        // then we don't add it to the uber jar
                        if (isBlockOrSF(relativePath) &&
                                file.relativize(metaInfDir).getNameCount() == 1) {
                            if (log.isDebugEnabled()) {
                                log.debug("Signature file " + file.toAbsolutePath() + " from app " +
                                        "dependency " + appDep + " will not be included in uberjar");
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        boolean transformed = transformedFromThisArchive != null
                                && transformedFromThisArchive.contains(relativePath);
                        if (!transformed) {
                            if (relativePath.startsWith("META-INF/services/") && relativePath.length() > 18) {
                                services.computeIfAbsent(relativePath, (u) -> new ArrayList<>())
                                        .add(Files.readAllBytes(file));
                                return FileVisitResult.CONTINUE;
                            } else if (!finalIgnoredEntries.contains(relativePath)) {
                                duplicateCatcher.computeIfAbsent(relativePath, (a) -> new HashSet<>())
                                        .add(appDep);
                                if (!seen.containsKey(relativePath)) {
                                    seen.put(relativePath, appDep.toString());
                                    Files.copy(file, runnerZipFs.getPath(relativePath),
                                            StandardCopyOption.REPLACE_EXISTING);
                                } else if (!relativePath.endsWith(".class")) {
                                    //for .class entries we warn as a group
                                    log.warn("Duplicate entry " + relativePath + " entry from " + appDep
                                            + " will be ignored. Existing file was provided by "
                                            + seen.get(relativePath));
                                }
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private JarBuildItem buildLegacyThinJar(CurateOutcomeBuildItem curateOutcomeBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            TransformedClassesBuildItem transformedClasses,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            PackageConfig packageConfig,
            ApplicationInfoBuildItem applicationInfo,
            List<GeneratedClassBuildItem> generatedClasses,
            List<GeneratedResourceBuildItem> generatedResources,
            MainClassBuildItem mainClassBuildItem) throws Exception {

        Path runnerJar = outputTargetBuildItem.getOutputDirectory()
                .resolve(outputTargetBuildItem.getBaseName() + packageConfig.runnerSuffix + ".jar");
        Path libDir = outputTargetBuildItem.getOutputDirectory().resolve("lib");
        Files.deleteIfExists(runnerJar);
        IoUtils.createOrEmptyDir(libDir);

        try (FileSystem runnerZipFs = ZipUtils.newZip(runnerJar)) {

            log.info("Building thin jar: " + runnerJar);

            doLegacyThinJarGeneration(curateOutcomeBuildItem, transformedClasses, applicationArchivesBuildItem, applicationInfo,
                    packageConfig, generatedResources, libDir, generatedClasses, runnerZipFs, mainClassBuildItem);
        }
        runnerJar.toFile().setReadable(true, false);

        return new JarBuildItem(runnerJar, null, libDir, PackageConfig.LEGACY, suffixToClassifier(packageConfig.runnerSuffix));
    }

    private JarBuildItem buildThinJar(CurateOutcomeBuildItem curateOutcomeBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            TransformedClassesBuildItem transformedClasses,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            PackageConfig packageConfig,
            ApplicationInfoBuildItem applicationInfo,
            List<GeneratedClassBuildItem> generatedClasses,
            List<GeneratedResourceBuildItem> generatedResources,
            List<AdditionalApplicationArchiveBuildItem> additionalApplicationArchiveBuildItems,
            MainClassBuildItem mainClassBuildItem) throws Exception {

        boolean rebuild = outputTargetBuildItem.isRebuild();

        Path buildDir;

        if (packageConfig.outputDirectory.isPresent()) {
            buildDir = outputTargetBuildItem.getOutputDirectory();
        } else {
            buildDir = outputTargetBuildItem.getOutputDirectory().resolve(DEFAULT_FAST_JAR_DIRECTORY_NAME);
        }

        //unmodified 3rd party dependencies
        Path libDir = buildDir.resolve(LIB);
        Path mainLib = libDir.resolve(MAIN);
        //parent first entries
        Path baseLib = libDir.resolve(BOOT_LIB);
        Files.createDirectories(baseLib);

        Path appDir = buildDir.resolve(APP);
        Path quarkus = buildDir.resolve(QUARKUS);
        Path userProviders = null;
        if (packageConfig.userProvidersDirectory.isPresent()) {
            userProviders = buildDir.resolve(packageConfig.userProvidersDirectory.get());
        }
        if (!rebuild) {
            IoUtils.createOrEmptyDir(buildDir);
            Files.createDirectories(mainLib);
            Files.createDirectories(baseLib);
            Files.createDirectories(appDir);
            Files.createDirectories(quarkus);
            if (userProviders != null) {
                Files.createDirectories(userProviders);
                //we add this dir so that it can be copied into container images if required
                //and will still be copied even if empty
                Files.createFile(userProviders.resolve(".keep"));
            }
        } else {
            IoUtils.createOrEmptyDir(quarkus);
        }
        Map<AppArtifactKey, List<Path>> copiedArtifacts = new HashMap<>();

        List<Path> jars = new ArrayList<>();
        List<Path> bootJars = new ArrayList<>();
        //we process in order of priority
        //transformed classes first
        if (!transformedClasses.getTransformedClassesByJar().isEmpty()) {
            Path transformedZip = quarkus.resolve(TRANSFORMED_BYTECODE_JAR);
            jars.add(transformedZip);
            try (FileSystem out = ZipUtils.newZip(transformedZip)) {
                for (Set<TransformedClassesBuildItem.TransformedClass> transformedSet : transformedClasses
                        .getTransformedClassesByJar().values()) {
                    for (TransformedClassesBuildItem.TransformedClass transformed : transformedSet) {
                        Path target = out.getPath(transformed.getFileName());
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.write(target, transformed.getData());
                    }
                }
            }
        }
        //now generated classes and resources
        Path generatedZip = quarkus.resolve(GENERATED_BYTECODE_JAR);
        jars.add(generatedZip);
        try (FileSystem out = ZipUtils.newZip(generatedZip)) {
            for (GeneratedClassBuildItem i : generatedClasses) {
                String fileName = i.getName().replace(".", "/") + ".class";
                Path target = out.getPath(fileName);
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.write(target, i.getClassData());
            }

            for (GeneratedResourceBuildItem i : generatedResources) {
                Path target = out.getPath(i.getName());
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.write(target, i.getClassData());
            }
        }
        //now the application classes
        Path runnerJar = appDir
                .resolve(outputTargetBuildItem.getBaseName() + ".jar");
        jars.add(runnerJar);

        if (!rebuild) {
            Set<String> finalIgnoredEntries = new HashSet<>(IGNORED_ENTRIES);
            packageConfig.userConfiguredIgnoredEntries.ifPresent(finalIgnoredEntries::addAll);
            try (FileSystem runnerZipFs = ZipUtils.newZip(runnerJar)) {
                for (Path root : applicationArchivesBuildItem.getRootArchive().getRootDirs()) {
                    copyFiles(root, runnerZipFs, null, finalIgnoredEntries);
                }
            }
        }

        StringBuilder classPath = new StringBuilder();
        for (AppDependency appDep : curateOutcomeBuildItem.getEffectiveModel().getUserDependencies()) {
            if (rebuild) {
                jars.addAll(appDep.getArtifact().getPaths().toList());
            } else {
                copyDependency(curateOutcomeBuildItem, copiedArtifacts, mainLib, baseLib, jars, true, classPath, appDep);
            }
            if (curateOutcomeBuildItem.getEffectiveModel().getRunnerParentFirstArtifacts()
                    .contains(appDep.getArtifact().getKey())) {
                bootJars.addAll(appDep.getArtifact().getPaths().toList());
            }
        }
        for (AdditionalApplicationArchiveBuildItem i : additionalApplicationArchiveBuildItems) {
            for (Path path : i.getPaths()) {
                if (!path.getParent().equals(userProviders)) {
                    throw new RuntimeException(
                            "Additional application archives can only be provided from the user providers directory. " + path
                                    + " is not present in " + userProviders);
                }
                jars.add(path);
            }
        }

        /*
         * There are some files like META-INF/microprofile-config.properties that usually don't exist in application
         * and yet are always looked up (spec compliance...) and due to the location in the jar,
         * the RunnerClassLoader needs to look into every jar to determine whether they exist or not.
         * In keeping true to the original design of the RunnerClassLoader which indexes the directory structure,
         * we just add a fail-fast path for files we know don't exist.
         *
         * TODO: if this gets more complex, we'll probably want a build item to carry this information instead of hard
         * coding it here
         */
        List<String> nonExistentResources = new ArrayList<>(1);
        Enumeration<URL> mpConfigURLs = Thread.currentThread().getContextClassLoader().getResources(MP_CONFIG_FILE);
        if (!mpConfigURLs.hasMoreElements()) {
            nonExistentResources.add(MP_CONFIG_FILE);
        }

        Path appInfo = buildDir.resolve(QuarkusEntryPoint.QUARKUS_APPLICATION_DAT);
        try (OutputStream out = Files.newOutputStream(appInfo)) {
            SerializedApplication.write(out, mainClassBuildItem.getClassName(), buildDir, jars, bootJars, nonExistentResources);
        }

        runnerJar.toFile().setReadable(true, false);
        Path initJar = buildDir.resolve(QUARKUS_RUN_JAR);
        if (!rebuild) {
            try (FileSystem runnerZipFs = ZipUtils.newZip(initJar)) {
                AppArtifact appArtifact = curateOutcomeBuildItem.getEffectiveModel().getAppArtifact();
                generateManifest(runnerZipFs, classPath.toString(), packageConfig, appArtifact,
                        QuarkusEntryPoint.class.getName(),
                        applicationInfo);
            }

            //now copy the deployment artifacts, if required
            if (packageConfig.type.equalsIgnoreCase(PackageConfig.MUTABLE_JAR)) {

                Path deploymentLib = libDir.resolve(DEPLOYMENT_LIB);
                Files.createDirectories(deploymentLib);
                for (AppDependency appDep : curateOutcomeBuildItem.getEffectiveModel().getFullDeploymentDeps()) {
                    copyDependency(curateOutcomeBuildItem, copiedArtifacts, deploymentLib, baseLib, jars, false, classPath,
                            appDep);
                }

                Map<AppArtifactKey, List<String>> relativePaths = new HashMap<>();
                for (Map.Entry<AppArtifactKey, List<Path>> e : copiedArtifacts.entrySet()) {
                    relativePaths.put(e.getKey(),
                            e.getValue().stream().map(s -> buildDir.relativize(s).toString().replace("\\", "/"))
                                    .collect(Collectors.toList()));
                }

                //now we serialize the data needed to build up the reaugmentation class path
                //first the app model
                PersistentAppModel model = new PersistentAppModel(outputTargetBuildItem.getBaseName(), relativePaths,
                        curateOutcomeBuildItem.getEffectiveModel(),
                        packageConfig.userProvidersDirectory.orElse(null), buildDir.relativize(runnerJar).toString());
                Path appmodelDat = deploymentLib.resolve(APPMODEL_DAT);
                try (OutputStream out = Files.newOutputStream(appmodelDat)) {
                    ObjectOutputStream obj = new ObjectOutputStream(out);
                    obj.writeObject(model);
                    obj.close();
                }
                //now the bootstrap CP
                //we just include all deployment deps, even though we only really need bootstrap
                //as we don't really have a resolved bootstrap CP
                //once we have the app model it will all be done in QuarkusClassLoader anyway
                Path deploymentCp = deploymentLib.resolve(DEPLOYMENT_CLASS_PATH_DAT);
                try (OutputStream out = Files.newOutputStream(deploymentCp)) {
                    ObjectOutputStream obj = new ObjectOutputStream(out);
                    List<String> paths = new ArrayList<>();
                    for (AppDependency i : curateOutcomeBuildItem.getEffectiveModel().getFullDeploymentDeps()) {
                        final List<String> list = relativePaths.get(i.getArtifact().getKey());
                        // some of the dependencies may have been filtered out
                        if (list != null) {
                            paths.addAll(list);
                        }
                    }
                    obj.writeObject(paths);
                    obj.close();
                }
                Path buildSystemProps = deploymentLib.resolve(BUILD_SYSTEM_PROPERTIES);
                try (OutputStream out = Files.newOutputStream(buildSystemProps)) {
                    outputTargetBuildItem.getBuildSystemProperties().store(out, "The original build properties");
                }
            }
        } else {
            //if it is a rebuild we might have classes

        }
        try (Stream<Path> files = Files.walk(buildDir)) {
            files.forEach(new Consumer<Path>() {
                @Override
                public void accept(Path path) {
                    path.toFile().setReadable(true, false);
                }
            });
        }
        return new JarBuildItem(initJar, null, libDir, packageConfig.type, null);
    }

    private void copyDependency(CurateOutcomeBuildItem curateOutcomeBuildItem, Map<AppArtifactKey, List<Path>> runtimeArtifacts,
            Path libDir, Path baseLib, List<Path> jars, boolean allowParentFirst, StringBuilder classPath, AppDependency appDep)
            throws IOException {
        final AppArtifact depArtifact = appDep.getArtifact();

        // Exclude files that are not jars (typically, we can have XML files here, see https://github.com/quarkusio/quarkus/issues/2852)
        if (!isAppDepAJar(depArtifact)) {
            return;
        }
        if (runtimeArtifacts.containsKey(depArtifact.getKey())) {
            return;
        }
        for (Path resolvedDep : depArtifact.getPaths()) {
            if (!Files.isDirectory(resolvedDep)) {
                if (allowParentFirst && curateOutcomeBuildItem.getEffectiveModel().getRunnerParentFirstArtifacts()
                        .contains(depArtifact.getKey())) {
                    final String fileName = depArtifact.getGroupId() + "." + resolvedDep.getFileName();
                    final Path targetPath = baseLib.resolve(fileName);
                    Files.copy(resolvedDep, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    classPath.append(" ").append(LIB).append("/").append(BOOT_LIB).append("/").append(fileName);
                    runtimeArtifacts.computeIfAbsent(depArtifact.getKey(), (s) -> new ArrayList<>()).add(targetPath);
                } else {
                    final String fileName = depArtifact.getGroupId() + "." + resolvedDep.getFileName();
                    final Path targetPath = libDir.resolve(fileName);
                    Files.copy(resolvedDep, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    jars.add(targetPath);
                    runtimeArtifacts.computeIfAbsent(depArtifact.getKey(), (s) -> new ArrayList<>()).add(targetPath);
                }
            } else {
                // This case can happen when we are building a jar from inside the Quarkus repository
                // and Quarkus Bootstrap's localProjectDiscovery has been set to true. In such a case
                // the non-jar dependencies are the Quarkus dependencies picked up on the file system
                // these should never be parent first

                final String fileName = depArtifact.getGroupId() + "." + resolvedDep.getFileName();
                final Path targetPath = libDir.resolve(fileName);
                runtimeArtifacts.computeIfAbsent(depArtifact.getKey(), (s) -> new ArrayList<>()).add(targetPath);
                jars.add(targetPath);
                try (FileSystem runnerZipFs = ZipUtils.newZip(targetPath)) {
                    Files.walkFileTree(resolvedDep, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                            new SimpleFileVisitor<Path>() {
                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                        throws IOException {
                                    final Path relativePath = resolvedDep.relativize(file);
                                    final Path targetPath = runnerZipFs.getPath(relativePath.toString());
                                    if (targetPath.getParent() != null) {
                                        Files.createDirectories(targetPath.getParent());
                                    }
                                    Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING); //replace only needed for testing
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                }
            }
        }
    }

    /**
     * Native images are built from a specially created jar file. This allows for changes in how the jar file is generated.
     *
     */
    @BuildStep
    public NativeImageSourceJarBuildItem buildNativeImageJar(CurateOutcomeBuildItem curateOutcomeBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            TransformedClassesBuildItem transformedClasses,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            ApplicationInfoBuildItem applicationInfo,
            PackageConfig packageConfig,
            List<GeneratedClassBuildItem> generatedClasses,
            List<GeneratedNativeImageClassBuildItem> nativeImageResources,
            List<GeneratedResourceBuildItem> generatedResources,
            MainClassBuildItem mainClassBuildItem,
            List<UberJarRequiredBuildItem> uberJarRequired) throws Exception {
        Path targetDirectory = outputTargetBuildItem.getOutputDirectory()
                .resolve(outputTargetBuildItem.getBaseName() + "-native-image-source-jar");
        IoUtils.createOrEmptyDir(targetDirectory);

        List<GeneratedClassBuildItem> allClasses = new ArrayList<>(generatedClasses);
        allClasses.addAll(nativeImageResources.stream()
                .map((s) -> new GeneratedClassBuildItem(true, s.getName(), s.getClassData()))
                .collect(Collectors.toList()));

        if (SystemUtils.IS_OS_WINDOWS) {
            log.warn("Uber JAR strategy is used for native image source JAR generation on Windows. This is done " +
                    "for the time being to work around a current GraalVM limitation on Windows concerning the " +
                    "maximum command length (see https://github.com/oracle/graal/issues/2387).");
            // Native image source jar generation with the uber jar strategy is provided as a workaround for Windows and
            // will be removed once https://github.com/oracle/graal/issues/2387 is fixed.
            final NativeImageSourceJarBuildItem nativeImageSourceJarBuildItem = buildNativeImageUberJar(curateOutcomeBuildItem,
                    outputTargetBuildItem, transformedClasses,
                    applicationArchivesBuildItem,
                    packageConfig, applicationInfo, allClasses, generatedResources, mainClassBuildItem, targetDirectory);
            // additionally copy any json config files to a location accessible by native-image tool during
            // native-image generation
            copyJsonConfigFiles(applicationArchivesBuildItem, targetDirectory);
            return nativeImageSourceJarBuildItem;
        } else {
            return buildNativeImageThinJar(curateOutcomeBuildItem, outputTargetBuildItem, transformedClasses,
                    applicationArchivesBuildItem,
                    applicationInfo, packageConfig, allClasses, generatedResources, mainClassBuildItem, targetDirectory);
        }
    }

    private NativeImageSourceJarBuildItem buildNativeImageThinJar(CurateOutcomeBuildItem curateOutcomeBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            TransformedClassesBuildItem transformedClasses,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            ApplicationInfoBuildItem applicationInfo,
            PackageConfig packageConfig,
            List<GeneratedClassBuildItem> allClasses,
            List<GeneratedResourceBuildItem> generatedResources,
            MainClassBuildItem mainClassBuildItem,
            Path targetDirectory) throws Exception {
        copyJsonConfigFiles(applicationArchivesBuildItem, targetDirectory);

        Path runnerJar = targetDirectory
                .resolve(outputTargetBuildItem.getBaseName() + packageConfig.runnerSuffix + ".jar");
        Path libDir = targetDirectory.resolve(LIB);
        Files.createDirectories(libDir);

        try (FileSystem runnerZipFs = ZipUtils.newZip(runnerJar)) {

            log.info("Building native image source jar: " + runnerJar);

            doLegacyThinJarGeneration(curateOutcomeBuildItem, transformedClasses, applicationArchivesBuildItem, applicationInfo,
                    packageConfig, generatedResources, libDir, allClasses, runnerZipFs, mainClassBuildItem);
        }
        runnerJar.toFile().setReadable(true, false);
        return new NativeImageSourceJarBuildItem(runnerJar, libDir);
    }

    private NativeImageSourceJarBuildItem buildNativeImageUberJar(CurateOutcomeBuildItem curateOutcomeBuildItem,
            OutputTargetBuildItem outputTargetBuildItem,
            TransformedClassesBuildItem transformedClasses,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            PackageConfig packageConfig,
            ApplicationInfoBuildItem applicationInfo,
            List<GeneratedClassBuildItem> generatedClasses,
            List<GeneratedResourceBuildItem> generatedResources,
            MainClassBuildItem mainClassBuildItem,
            Path targetDirectory) throws Exception {
        //we use the -runner jar name, unless we are building both types
        Path runnerJar = targetDirectory
                .resolve(outputTargetBuildItem.getBaseName() + packageConfig.runnerSuffix + ".jar");

        buildUberJar0(curateOutcomeBuildItem,
                transformedClasses,
                applicationArchivesBuildItem,
                packageConfig,
                applicationInfo,
                generatedClasses,
                generatedResources,
                mainClassBuildItem,
                runnerJar);

        return new NativeImageSourceJarBuildItem(runnerJar, null);
    }

    /**
     * This is done in order to make application specific native image configuration files available to the native-image tool
     * without the user needing to know any specific paths.
     * The files that are copied don't end up in the native image unless the user specifies they are needed, all this method
     * does is copy them to a convenient location
     */
    private void copyJsonConfigFiles(ApplicationArchivesBuildItem applicationArchivesBuildItem, Path thinJarDirectory)
            throws IOException {
        for (Path root : applicationArchivesBuildItem.getRootArchive().getRootDirs()) {
            try (Stream<Path> stream = Files.find(root, 1, IS_JSON_FILE_PREDICATE)) {
                stream.forEach(new Consumer<Path>() {
                    @Override
                    public void accept(Path jsonPath) {
                        try {
                            Files.copy(jsonPath, thinJarDirectory.resolve(jsonPath.getFileName().toString()));
                        } catch (IOException e) {
                            throw new UncheckedIOException(
                                    "Unable to copy json config file from " + jsonPath + " to " + thinJarDirectory,
                                    e);
                        }
                    }
                });
            }
        }
    }

    private void doLegacyThinJarGeneration(CurateOutcomeBuildItem curateOutcomeBuildItem,
            TransformedClassesBuildItem transformedClasses,
            ApplicationArchivesBuildItem applicationArchivesBuildItem,
            ApplicationInfoBuildItem applicationInfo,
            PackageConfig packageConfig,
            List<GeneratedResourceBuildItem> generatedResources,
            Path libDir,
            List<GeneratedClassBuildItem> allClasses,
            FileSystem runnerZipFs,
            MainClassBuildItem mainClassBuildItem)
            throws BootstrapDependencyProcessingException, AppModelResolverException, IOException {
        final Map<String, String> seen = new HashMap<>();
        final StringBuilder classPath = new StringBuilder();
        final Map<String, List<byte[]>> services = new HashMap<>();

        final List<AppDependency> appDeps = curateOutcomeBuildItem.getEffectiveModel().getUserDependencies();
        final Set<String> finalIgnoredEntries = new HashSet<>(IGNORED_ENTRIES);
        packageConfig.userConfiguredIgnoredEntries.ifPresent(finalIgnoredEntries::addAll);

        copyLibraryJars(runnerZipFs, transformedClasses, libDir, classPath, appDeps, services, finalIgnoredEntries);

        AppArtifact appArtifact = curateOutcomeBuildItem.getEffectiveModel().getAppArtifact();
        // the manifest needs to be the first entry in the jar, otherwise JarInputStream does not work properly
        // see https://bugs.openjdk.java.net/browse/JDK-8031748
        generateManifest(runnerZipFs, classPath.toString(), packageConfig, appArtifact, mainClassBuildItem.getClassName(),
                applicationInfo);

        copyCommonContent(runnerZipFs, services, applicationArchivesBuildItem, transformedClasses, allClasses,
                generatedResources, seen, finalIgnoredEntries);
    }

    private void copyLibraryJars(FileSystem runnerZipFs, TransformedClassesBuildItem transformedClasses, Path libDir,
            StringBuilder classPath, List<AppDependency> appDeps, Map<String, List<byte[]>> services,
            Set<String> ignoredEntries) throws IOException {

        for (AppDependency appDep : appDeps) {
            final AppArtifact depArtifact = appDep.getArtifact();

            // Exclude files that are not jars (typically, we can have XML files here, see https://github.com/quarkusio/quarkus/issues/2852)
            if (!isAppDepAJar(depArtifact)) {
                continue;
            }

            for (Path resolvedDep : depArtifact.getPaths()) {
                if (!Files.isDirectory(resolvedDep)) {
                    Set<String> transformedFromThisArchive = transformedClasses.getTransformedFilesByJar().get(resolvedDep);
                    if (transformedFromThisArchive == null || transformedFromThisArchive.isEmpty()) {
                        final String fileName = depArtifact.getGroupId() + "." + resolvedDep.getFileName();
                        final Path targetPath = libDir.resolve(fileName);
                        Files.copy(resolvedDep, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        classPath.append(" lib/").append(fileName);
                    } else {
                        //we have transformed classes, we need to handle them correctly
                        final String fileName = "modified-" + depArtifact.getGroupId() + "." + resolvedDep.getFileName();
                        final Path targetPath = libDir.resolve(fileName);
                        classPath.append(" lib/").append(fileName);
                        filterZipFile(resolvedDep, targetPath, transformedFromThisArchive);
                    }
                } else {
                    // This case can happen when we are building a jar from inside the Quarkus repository
                    // and Quarkus Bootstrap's localProjectDiscovery has been set to true. In such a case
                    // the non-jar dependencies are the Quarkus dependencies picked up on the file system
                    Files.walkFileTree(resolvedDep, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                            new SimpleFileVisitor<Path>() {
                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                        throws IOException {
                                    final Path relativePath = resolvedDep.relativize(file);
                                    final String relativeUri = toUri(relativePath);
                                    if (ignoredEntries.contains(relativeUri)) {
                                        return FileVisitResult.CONTINUE;
                                    }
                                    if (relativeUri.startsWith("META-INF/services/") && relativeUri.length() > 18) {
                                        services.computeIfAbsent(relativeUri, (u) -> new ArrayList<>())
                                                .add(Files.readAllBytes(file));
                                    } else if (file.getFileName().toString().endsWith(".class")) {
                                        final Path targetPath = runnerZipFs.getPath(relativePath.toString());
                                        if (targetPath.getParent() != null) {
                                            Files.createDirectories(targetPath.getParent());
                                        }
                                        Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING); //replace only needed for testing
                                    }
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                }
            }
        }
    }

    private void copyCommonContent(FileSystem runnerZipFs, Map<String, List<byte[]>> services,
            ApplicationArchivesBuildItem appArchives, TransformedClassesBuildItem transformedClassesBuildItem,
            List<GeneratedClassBuildItem> generatedClasses,
            List<GeneratedResourceBuildItem> generatedResources, Map<String, String> seen,
            Set<String> ignoredEntries)
            throws IOException {

        //TODO: this is probably broken in gradle
        //        if (Files.exists(augmentOutcome.getConfigDir())) {
        //            copyFiles(augmentOutcome.getConfigDir(), runnerZipFs, services);
        //        }
        for (Set<TransformedClassesBuildItem.TransformedClass> transformed : transformedClassesBuildItem
                .getTransformedClassesByJar().values()) {
            for (TransformedClassesBuildItem.TransformedClass i : transformed) {
                Path target = runnerZipFs.getPath(i.getFileName());
                handleParent(runnerZipFs, i.getFileName(), seen);
                try (final OutputStream out = wrapForJDK8232879(Files.newOutputStream(target, DEFAULT_OPEN_OPTIONS))) {
                    out.write(i.getData());
                }
                seen.put(i.getFileName(), "Current Application");
            }
        }
        for (GeneratedClassBuildItem i : generatedClasses) {
            String fileName = i.getName().replace(".", "/") + ".class";
            seen.put(fileName, "Current Application");
            Path target = runnerZipFs.getPath(fileName);
            handleParent(runnerZipFs, fileName, seen);
            if (Files.exists(target)) {
                continue;
            }
            try (final OutputStream os = wrapForJDK8232879(Files.newOutputStream(target, DEFAULT_OPEN_OPTIONS))) {
                os.write(i.getClassData());
            }
        }

        for (GeneratedResourceBuildItem i : generatedResources) {
            if (ignoredEntries.contains(i.getName())) {
                continue;
            }
            Path target = runnerZipFs.getPath(i.getName());
            handleParent(runnerZipFs, i.getName(), seen);
            if (Files.exists(target)) {
                continue;
            }
            if (i.getName().startsWith("META-INF/services")) {
                services.computeIfAbsent(i.getName(), (u) -> new ArrayList<>()).add(i.getClassData());
            } else {
                try (final OutputStream os = wrapForJDK8232879(Files.newOutputStream(target, DEFAULT_OPEN_OPTIONS))) {
                    os.write(i.getClassData());
                }
            }
        }

        for (Path root : appArchives.getRootArchive().getRootDirs()) {
            copyFiles(root, runnerZipFs, services, ignoredEntries);
        }

        for (Map.Entry<String, List<byte[]>> entry : services.entrySet()) {
            try (final OutputStream os = wrapForJDK8232879(
                    Files.newOutputStream(runnerZipFs.getPath(entry.getKey()), DEFAULT_OPEN_OPTIONS))) {
                for (byte[] i : entry.getValue()) {
                    os.write(i);
                    os.write('\n');
                }
            }
        }
    }

    private void handleParent(FileSystem runnerZipFs, String fileName, Map<String, String> seen) throws IOException {
        for (int i = 0; i < fileName.length(); ++i) {
            if (fileName.charAt(i) == '/') {
                String dir = fileName.substring(0, i);
                if (!seen.containsKey(dir)) {
                    seen.put(dir, "Current Application");
                    Files.createDirectories(runnerZipFs.getPath(dir));
                }
            }
        }
    }

    private void filterZipFile(Path resolvedDep, Path targetPath, Set<String> transformedFromThisArchive) {

        try {
            byte[] buffer = new byte[10000];
            try (ZipFile in = new ZipFile(resolvedDep.toFile())) {
                try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(targetPath.toFile()))) {
                    Enumeration<? extends ZipEntry> entries = in.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (!transformedFromThisArchive.contains(entry.getName())) {
                            out.putNextEntry(entry);
                            try (InputStream inStream = in.getInputStream(entry)) {
                                int r = 0;
                                while ((r = inStream.read(buffer)) > 0) {
                                    out.write(buffer, 0, r);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Manifest generation is quite simple : we just have to push some attributes in manifest.
     * However, it gets a little more complex if the manifest preexists.
     * So we first try to see if a manifest exists, and otherwise create a new one.
     *
     * <b>BEWARE</b> this method should be invoked after file copy from target/classes and so on.
     * Otherwise this manifest manipulation will be useless.
     */
    private void generateManifest(FileSystem runnerZipFs, final String classPath, PackageConfig config, AppArtifact appArtifact,
            String mainClassName,
            ApplicationInfoBuildItem applicationInfo)
            throws IOException {
        final Path manifestPath = runnerZipFs.getPath("META-INF", "MANIFEST.MF");
        final Manifest manifest = new Manifest();
        if (Files.exists(manifestPath)) {
            try (InputStream is = Files.newInputStream(manifestPath)) {
                manifest.read(is);
            }
            Files.delete(manifestPath);
        } else {
            Files.createDirectories(runnerZipFs.getPath("META-INF"));
        }
        Files.createDirectories(manifestPath.getParent());
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (attributes.containsKey(Attributes.Name.CLASS_PATH)) {
            log.warn(
                    "Your MANIFEST.MF already defined a CLASS_PATH entry. Quarkus has overwritten this existing entry.");
        }
        attributes.put(Attributes.Name.CLASS_PATH, classPath);
        if (attributes.containsKey(Attributes.Name.MAIN_CLASS)) {
            String existingMainClass = attributes.getValue(Attributes.Name.MAIN_CLASS);
            if (!mainClassName.equals(existingMainClass)) {
                log.warn("Your MANIFEST.MF already defined a MAIN_CLASS entry. Quarkus has overwritten your existing entry.");
            }
        }
        attributes.put(Attributes.Name.MAIN_CLASS, mainClassName);
        if (config.manifest.addImplementationEntries && !attributes.containsKey(Attributes.Name.IMPLEMENTATION_TITLE)) {
            String name = ApplicationInfoBuildItem.UNSET_VALUE.equals(applicationInfo.getName()) ? appArtifact.getArtifactId()
                    : applicationInfo.getName();
            attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, name);
        }
        if (config.manifest.addImplementationEntries && !attributes.containsKey(Attributes.Name.IMPLEMENTATION_VERSION)) {
            String version = ApplicationInfoBuildItem.UNSET_VALUE.equals(applicationInfo.getVersion())
                    ? appArtifact.getVersion()
                    : applicationInfo.getVersion();
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        }
        if (config.manifest.manifestSections.size() > 0) {
            for (String sectionName : config.manifest.manifestSections.keySet()) {
                for (Map.Entry<String, String> entry : config.manifest.manifestSections.get(sectionName).entrySet()) {
                    Attributes attribs = manifest.getEntries().computeIfAbsent(sectionName, k -> new Attributes());
                    attribs.putValue(entry.getKey(), entry.getValue());
                }
            }
        }
        try (final OutputStream os = wrapForJDK8232879(Files.newOutputStream(manifestPath, DEFAULT_OPEN_OPTIONS))) {
            manifest.write(os);
        }
    }

    /**
     * Copy files from {@code dir} to {@code fs}, filtering out service providers into the given map.
     *
     * @param dir the source directory
     * @param fs the destination filesystem
     * @param services the services map
     * @throws IOException if an error occurs
     */
    private void copyFiles(Path dir, FileSystem fs, Map<String, List<byte[]>> services, Set<String> ignoredEntries)
            throws IOException {
        try (Stream<Path> fileTreeElements = Files.walk(dir)) {
            fileTreeElements.forEach(new Consumer<Path>() {
                @Override
                public void accept(Path path) {
                    final Path file = dir.relativize(path);
                    final String relativePath = toUri(file);
                    if (relativePath.isEmpty() || ignoredEntries.contains(relativePath)) {
                        return;
                    }
                    try {
                        if (Files.isDirectory(path)) {
                            addDir(fs, relativePath);
                        } else {
                            if (relativePath.startsWith("META-INF/services/") && relativePath.length() > 18
                                    && services != null) {
                                final byte[] content;
                                try {
                                    content = Files.readAllBytes(path);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                                services.computeIfAbsent(relativePath, (u) -> new ArrayList<>()).add(content);
                            } else if (!relativePath.equals("META-INF/INDEX.LIST")) {
                                //TODO: auto generate INDEX.LIST
                                //this may have implications for Camel though, as they change the layout
                                //also this is only really relevant for the thin jar layout
                                Path target = fs.getPath(relativePath);
                                if (!Files.exists(target)) {
                                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        } catch (RuntimeException re) {
            final Throwable cause = re.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw re;
        }
    }

    private void addDir(FileSystem fs, final String relativePath)
            throws IOException {
        final Path targetDir = fs.getPath(relativePath);
        try {
            Files.createDirectory(targetDir);
        } catch (FileAlreadyExistsException e) {
            if (!Files.isDirectory(targetDir)) {
                throw e;
            }
        }
    }

    private static String toUri(Path path) {
        if (path.isAbsolute()) {
            return path.toUri().getPath();
        }
        if (path.getNameCount() == 0) {
            return "";
        }
        return toUri(new StringBuilder(), path, 0).toString();
    }

    private static StringBuilder toUri(StringBuilder b, Path path, int seg) {
        b.append(path.getName(seg));
        if (seg < path.getNameCount() - 1) {
            b.append('/');
            toUri(b, path, seg + 1);
        }
        return b;
    }

    static class JarRequired implements BooleanSupplier {

        private final PackageConfig packageConfig;

        JarRequired(PackageConfig packageConfig) {
            this.packageConfig = packageConfig;
        }

        @Override
        public boolean getAsBoolean() {
            return packageConfig.isAnyJarType();
        }
    }

    // same as the impl in sun.security.util.SignatureFileVerifier#isBlockOrSF()
    static boolean isBlockOrSF(final String s) {
        if (s == null) {
            return false;
        }
        return s.endsWith(".SF")
                || s.endsWith(".DSA")
                || s.endsWith(".RSA")
                || s.endsWith(".EC");
    }

    private static class IsJsonFilePredicate implements BiPredicate<Path, BasicFileAttributes> {

        @Override
        public boolean test(Path path, BasicFileAttributes basicFileAttributes) {
            return basicFileAttributes.isRegularFile() && path.toString().endsWith(".json");
        }
    }
}
