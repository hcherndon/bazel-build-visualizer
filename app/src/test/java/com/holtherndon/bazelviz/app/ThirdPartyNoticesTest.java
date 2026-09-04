package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Keeps reviewed licenses and notices from disappearing when dependency jars are merged. */
final class ThirdPartyNoticesTest {

  private static final String PREFIX = "META-INF/third-party/";
  private static final String ICON_PREFIX = "com/holtherndon/bazelviz/ui/repository/icons/";

  private static final List<String> MATERIAL_REPOSITORY_ICONS =
      List.of(
          "audio.svg",
          "c.svg",
          "console.svg",
          "cpp.svg",
          "csharp.svg",
          "css.svg",
          "database.svg",
          "docker.svg",
          "document.svg",
          "folder-base.svg",
          "font.svg",
          "go.svg",
          "html.svg",
          "image.svg",
          "java.svg",
          "javascript.svg",
          "json.svg",
          "kotlin.svg",
          "log.svg",
          "makefile.svg",
          "markdown.svg",
          "pdf.svg",
          "proto.svg",
          "python.svg",
          "ruby.svg",
          "rust.svg",
          "settings.svg",
          "typescript.svg",
          "video.svg",
          "xml.svg",
          "yaml.svg",
          "zip.svg");

  private static final String MATERIAL_REPOSITORY_ICONS_SHA256 =
      "4647f53dbe8c59d9f81793dcc66f7f318d760bc0119700f0858af8a819a4007a";

  private static final Map<String, String> PROJECT_REPOSITORY_ICONS =
      Map.of(
          "bazel.svg",
          "d09a2de7f32654ef79ab238df45df78678c45ef6693731da7ff49e3a3c134876",
          "bazel-folder.svg",
          "97998954b78fcca45a8ad0389fc0b876f15a62c7782c0dc94070e4ede7f4e4ce");

  private static final Map<String, String> UPSTREAM_LEGAL_FILES =
      Map.ofEntries(
          Map.entry(
              "AIRCOMPRESSOR-APACHE-2.0.txt",
              "8c6db340475136df3c1201d458fa5755698eace76e510471ecc9d857d6083dac"),
          Map.entry(
              "AIRCOMPRESSOR-NOTICE.md",
              "304b0042470fde5507ff68f05d5e3a5545494d8f3c8f11f5cd4b7789da0b4ea1"),
          Map.entry(
              "ANIMAL-SNIFFER-MIT.txt",
              "0d17e86dbd1f3504cfa311e67827e1569ad27bbfff29cc405194e4a2549e9df8"),
          Map.entry(
              "APACHE-COMMONS-LANG-3.4-NOTICE.txt",
              "e141031bc88de36eb113239bf9733a0c85d743964bb5509f3f00e39f144e3513"),
          Map.entry(
              "APACHE-2.0.txt", "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
          Map.entry(
              "AUTOCOMPLETE-BSD-3-CLAUSE.txt",
              "ad28a644b986a728d40c7b58d0104300150ecb5ee2f22cccbd3d72de10c084de"),
          Map.entry(
              "BAZEL-APACHE-2.0.txt",
              "9fe6dae7684812d0117f5a0611aa773656776545797608f201fef88fa7363e0b"),
          Map.entry(
              "BORINGSSL-LICENSE.txt",
              "827c8d8fc207c2392794eef9e00fe246f9f61fdcc132556c275be3dd8c3cd97f"),
          Map.entry(
              "EPL-2.0.txt", "0becf16567beb77fa252b7664631dd177c8f9a1889e48995b45379c7130e5303"),
          Map.entry(
              "FLATLAF-APACHE-2.0.txt",
              "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
          Map.entry(
              "GRPC-NETTY-SHADED-APACHE-2.0.txt",
              "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
          Map.entry(
              "GRPC-NETTY-SHADED-NOTICE.txt",
              "45d84f3f695ff7b50308c68727227e57b5e5be6739f9fcfe8b8a21068803da65"),
          Map.entry(
              "GOOGLEAPIS-APACHE-2.0.txt",
              "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
          Map.entry(
              "JEDITERM-APACHE-2.0.txt",
              "770af8291f708538d8ff885a0bbc4e045cd700531741c4f99528d435c14d7f55"),
          Map.entry(
              "JETBRAINS-ANNOTATIONS-LICENSE.txt",
              "8c1e966c7855fb54027bcaf6ebe7a43abe4785791e8cf9148c363761d493d097"),
          Map.entry(
              "JNA-APACHE-2.0.txt",
              "0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594"),
          Map.entry(
              "JNA-LIBFFI-3.4.4-LICENSE.txt",
              "2c9c2acb9743e6b007b91350475308aee44691d96aa20eacef8e199988c8c388"),
          Map.entry(
              "JNA-LICENSE.txt",
              "521bb271ac56e0e29a1b1b688b94af17d00d378fc8e63478d8c8b2a7c4a229d0"),
          Map.entry(
              "JSVG-MIT.txt", "4ef80d54216cb7a7063cd8b068b6abc1236d99a416841a81766234846e32f3c5"),
          Map.entry(
              "KOTLIN-LICENSE.txt",
              "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
          Map.entry(
              "KOTLIN-NOTICE.txt",
              "0b09a83d3ef7795c7dec65e815866597c066c53898f2852a76599b8f5552941a"),
          Map.entry(
              "LOGBACK-LICENSE.txt",
              "f1eedf4f2cb9e901d6ae549a06d0c20dcde0136d37e38b50adaf51d4673f66d2"),
          Map.entry(
              "MATERIAL-ICON-THEME-MIT.txt",
              "cdab3014d4f69b49dde2b85e81792208c72de613aa6aed7f7a9b5c6609b89670"),
          Map.entry(
              "MAVEN-WRAPPER-APACHE-2.0.txt",
              "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
          Map.entry(
              "PERFMARK-APACHE-2.0.txt",
              "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
          Map.entry(
              "PPROF-APACHE-2.0.txt",
              "8c6db340475136df3c1201d458fa5755698eace76e510471ecc9d857d6083dac"),
          Map.entry(
              "PROTOBUF-BSD-3-CLAUSE.txt",
              "6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d"),
          Map.entry(
              "PTY4J-EPL-1.0.txt",
              "44277b2bec6093e4ac313afec251a4de599d24c4e768f8574d95b13a9d2d97b5"),
          Map.entry(
              "PTY4J-NOTICE.txt",
              "81c2565e0cf16cd6d3a0e4601197ff0e0a7ec8bab04d4ed85d74f48977f83015"),
          Map.entry(
              "RSYNTAXTEXTAREA-BSD-3-CLAUSE.txt",
              "cf2db2da16a070dbded576b7cbf4ece41dfc4c2063d712fa8e0d20fe4239b393"),
          Map.entry(
              "SLF4J-LICENSE.txt",
              "6fbe2eaf44b193b8a40eed9208f52848572224ad8d7672dd09418aa174847e73"),
          Map.entry(
              "SQL-FORMATTER-MIT.txt",
              "e28764ff43225205a81cdc025509c4de751a17e638efde0a2ca4740cf7ea0e55"),
          Map.entry(
              "SQLITE-JDBC-APACHE-2.0.txt",
              "3ddf9be5c28fe27dad143a5dc76eea25222ad1dd68934a047064e56ed2fa40c5"),
          Map.entry(
              "SQLITE-JDBC-NOTICE.txt",
              "a0670cf020edf700524c43c85321c2ba027a8bace5682001db60875d4a9fe52b"),
          Map.entry(
              "SQLITE-JDBC-ZENTUS-BSD.txt",
              "89167dab92289c7e5e2b65b044f0856b703d05e5d5e35c3548e73d9c7d2f5048"),
          Map.entry(
              "SQLITE-PUBLIC-DOMAIN.md",
              "ee6af51062b30d532991face5164136ae6f84e265ecf8abe89dc69dac45ca1e7"),
          Map.entry(
              "TOMCAT-NATIVE-APACHE-2.0.txt",
              "43070e2d4e532684de521b885f385d0841030efa2b1a20bafb76133a5e1379c1"),
          Map.entry(
              "WINDOWS-TERMINAL-LICENSE.txt",
              "ad0cf28f3381ca9bb0bf101d127402d44c17bfa0991e1a00bff7ae6679e9dada"),
          Map.entry(
              "WINDOWS-TERMINAL-NOTICE.md",
              "158036acf1095ff84839831f4bbaa8d116015e4ae14ab3d944664e05ed12136e"),
          Map.entry(
              "WINPTY-LICENSE.txt",
              "c39e428064b4f3e4fe81a975bf0fd3b845922b431bc4d9a7ffc8bfb091981836"));

  private static final Map<String, String> ORIGINAL_ARTIFACT_LEGAL_FILES =
      Map.of(
          "META-INF/LICENSE",
          "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
          "META-INF/LICENSE.txt",
          "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4",
          "META-INF/NOTICE.txt",
          "45d84f3f695ff7b50308c68727227e57b5e5be6739f9fcfe8b8a21068803da65",
          "META-INF/license/LICENSE.boringssl.txt",
          "827c8d8fc207c2392794eef9e00fe246f9f61fdcc132556c275be3dd8c3cd97f",
          "META-INF/license/LICENSE.mvn-wrapper.txt",
          "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
          "META-INF/license/LICENSE.tomcat-native.txt",
          "43070e2d4e532684de521b885f385d0841030efa2b1a20bafb76133a5e1379c1",
          "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE",
          "3ddf9be5c28fe27dad143a5dc76eea25222ad1dd68934a047064e56ed2fa40c5",
          "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus",
          "89167dab92289c7e5e2b65b044f0856b703d05e5d5e35c3548e73d9c7d2f5048");

  private static final List<RuntimeArtifact> RUNTIME_ARTIFACTS =
      List.of(
          artifact("ch.qos.logback:logback-classic:1.6.3", "ch/qos/logback/classic/Logger.class"),
          artifact("ch.qos.logback:logback-core:1.6.3", "ch/qos/logback/core/Appender.class"),
          artifact(
              "com.fifesoft:autocomplete:3.3.3", "org/fife/ui/autocomplete/AutoCompletion.class"),
          artifact(
              "com.fifesoft:rsyntaxtextarea:3.6.1",
              "org/fife/ui/rsyntaxtextarea/RSyntaxTextArea.class"),
          artifact("com.formdev:flatlaf:3.7.2", "com/formdev/flatlaf/FlatLaf.class"),
          artifact(
              "com.formdev:flatlaf-extras:3.7.2", "com/formdev/flatlaf/extras/FlatSVGIcon.class"),
          artifact(
              "com.github.vertical-blank:sql-formatter:2.0.5",
              "com/github/vertical_blank/sqlformatter/SqlFormatter.class"),
          artifact("com.github.weisj:jsvg:2.1.0", "com/github/weisj/jsvg/SVGDocument.class"),
          artifact("com.google.android:annotations:4.1.1.4", "android/annotation/TargetApi.class"),
          artifact(
              "com.google.api.grpc:proto-google-common-protos:2.64.1",
              "com/google/api/AnnotationsProto.class"),
          artifact("com.google.code.findbugs:jsr305:3.0.2", "javax/annotation/Nonnull.class"),
          artifact("com.google.code.gson:gson:2.14.0", "com/google/gson/Gson.class"),
          artifact(
              "com.google.errorprone:error_prone_annotations:2.50.0",
              "com/google/errorprone/annotations/CanIgnoreReturnValue.class"),
          artifact(
              "com.google.guava:failureaccess:1.0.3",
              "com/google/common/util/concurrent/internal/InternalFutureFailureAccess.class"),
          artifact(
              "com.google.guava:guava:33.6.0-android",
              "com/google/common/collect/ImmutableList.class"),
          artifact(
              "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava",
              "META-INF/maven/com.google.guava/listenablefuture/pom.properties"),
          artifact(
              "com.google.j2objc:j2objc-annotations:3.1",
              "com/google/j2objc/annotations/Weak.class"),
          artifact("com.google.protobuf:protobuf-java:4.36.0", "com/google/protobuf/Message.class"),
          artifact(
              "com.google.protobuf:protobuf-java-util:4.36.0",
              "com/google/protobuf/util/JsonFormat.class"),
          artifact(
              "io.airlift:aircompressor:2.0.3", "io/airlift/compress/zstd/ZstdDecompressor.class"),
          artifact("io.grpc:grpc-api:1.83.1", "io/grpc/Channel.class"),
          artifact("io.grpc:grpc-context:1.83.1", null),
          artifact("io.grpc:grpc-core:1.83.1", "io/grpc/internal/ManagedChannelImpl.class"),
          artifact(
              "io.grpc:grpc-netty-shaded:1.83.1",
              "io/grpc/netty/shaded/io/grpc/netty/NettyChannelBuilder.class"),
          artifact("io.grpc:grpc-protobuf:1.83.1", "io/grpc/protobuf/ProtoUtils.class"),
          artifact(
              "io.grpc:grpc-protobuf-lite:1.83.1", "io/grpc/protobuf/lite/ProtoLiteUtils.class"),
          artifact("io.grpc:grpc-stub:1.83.1", "io/grpc/stub/AbstractStub.class"),
          artifact("io.grpc:grpc-util:1.83.1", "io/grpc/util/MutableHandlerRegistry.class"),
          artifact("io.perfmark:perfmark-api:0.27.0", "io/perfmark/PerfMark.class"),
          artifact("net.java.dev.jna:jna:5.14.0", "com/sun/jna/Native.class"),
          artifact(
              "net.java.dev.jna:jna-platform:5.14.0",
              "com/sun/jna/platform/win32/Win32Exception.class"),
          artifact(
              "org.codehaus.mojo:animal-sniffer-annotations:1.27",
              "org/codehaus/mojo/animal_sniffer/IgnoreJRERequirement.class"),
          artifact("org.jetbrains:annotations:24.0.1", "org/jetbrains/annotations/NotNull.class"),
          artifact(
              "org.jetbrains.jediterm:jediterm-core:3.74", "com/jediterm/terminal/Terminal.class"),
          artifact(
              "org.jetbrains.jediterm:jediterm-ui:3.74",
              "com/jediterm/terminal/ui/JediTermWidget.class"),
          artifact("org.jetbrains.kotlin:kotlin-stdlib:2.4.0", "kotlin/Unit.class"),
          artifact("org.jetbrains.pty4j:pty4j:0.13.8", "com/pty4j/PtyProcess.class"),
          artifact("org.jspecify:jspecify:1.0.0", "org/jspecify/annotations/Nullable.class"),
          artifact("org.slf4j:slf4j-api:2.0.18", "org/slf4j/Logger.class"),
          artifact("org.xerial:sqlite-jdbc:3.53.2.1", "org/sqlite/JDBC.class"));

  private static final Map<String, String> RUNTIME_LEGAL_FILES =
      Map.ofEntries(
          legal("ch.qos.logback:logback-classic:1.6.3", "LOGBACK-LICENSE.txt"),
          legal("ch.qos.logback:logback-core:1.6.3", "LOGBACK-LICENSE.txt"),
          legal("com.fifesoft:autocomplete:3.3.3", "AUTOCOMPLETE-BSD-3-CLAUSE.txt"),
          legal("com.fifesoft:rsyntaxtextarea:3.6.1", "RSYNTAXTEXTAREA-BSD-3-CLAUSE.txt"),
          legal("com.formdev:flatlaf:3.7.2", "FLATLAF-APACHE-2.0.txt"),
          legal("com.formdev:flatlaf-extras:3.7.2", "FLATLAF-APACHE-2.0.txt"),
          legal("com.github.vertical-blank:sql-formatter:2.0.5", "SQL-FORMATTER-MIT.txt"),
          legal("com.github.weisj:jsvg:2.1.0", "JSVG-MIT.txt"),
          legal("com.google.android:annotations:4.1.1.4", "APACHE-2.0.txt"),
          legal("com.google.api.grpc:proto-google-common-protos:2.64.1", "APACHE-2.0.txt"),
          legal("com.google.code.findbugs:jsr305:3.0.2", "APACHE-2.0.txt"),
          legal("com.google.code.gson:gson:2.14.0", "APACHE-2.0.txt"),
          legal("com.google.errorprone:error_prone_annotations:2.50.0", "APACHE-2.0.txt"),
          legal("com.google.guava:failureaccess:1.0.3", "APACHE-2.0.txt"),
          legal("com.google.guava:guava:33.6.0-android", "APACHE-2.0.txt"),
          legal(
              "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava",
              "APACHE-2.0.txt"),
          legal("com.google.j2objc:j2objc-annotations:3.1", "APACHE-2.0.txt"),
          legal("com.google.protobuf:protobuf-java:4.36.0", "PROTOBUF-BSD-3-CLAUSE.txt"),
          legal("com.google.protobuf:protobuf-java-util:4.36.0", "PROTOBUF-BSD-3-CLAUSE.txt"),
          legal("io.airlift:aircompressor:2.0.3", "AIRCOMPRESSOR-APACHE-2.0.txt"),
          legal("io.grpc:grpc-api:1.83.1", "APACHE-2.0.txt"),
          legal("io.grpc:grpc-context:1.83.1", "APACHE-2.0.txt"),
          legal("io.grpc:grpc-core:1.83.1", "APACHE-2.0.txt"),
          legal("io.grpc:grpc-netty-shaded:1.83.1", "GRPC-NETTY-SHADED-APACHE-2.0.txt"),
          legal("io.grpc:grpc-protobuf:1.83.1", "APACHE-2.0.txt"),
          legal("io.grpc:grpc-protobuf-lite:1.83.1", "APACHE-2.0.txt"),
          legal("io.grpc:grpc-stub:1.83.1", "APACHE-2.0.txt"),
          legal("io.grpc:grpc-util:1.83.1", "APACHE-2.0.txt"),
          legal("io.perfmark:perfmark-api:0.27.0", "PERFMARK-APACHE-2.0.txt"),
          legal("net.java.dev.jna:jna:5.14.0", "JNA-LICENSE.txt"),
          legal("net.java.dev.jna:jna-platform:5.14.0", "JNA-LICENSE.txt"),
          legal("org.codehaus.mojo:animal-sniffer-annotations:1.27", "ANIMAL-SNIFFER-MIT.txt"),
          legal("org.jetbrains:annotations:24.0.1", "JETBRAINS-ANNOTATIONS-LICENSE.txt"),
          legal("org.jetbrains.jediterm:jediterm-core:3.74", "JEDITERM-APACHE-2.0.txt"),
          legal("org.jetbrains.jediterm:jediterm-ui:3.74", "JEDITERM-APACHE-2.0.txt"),
          legal("org.jetbrains.kotlin:kotlin-stdlib:2.4.0", "KOTLIN-LICENSE.txt"),
          legal("org.jetbrains.pty4j:pty4j:0.13.8", "PTY4J-EPL-1.0.txt"),
          legal("org.jspecify:jspecify:1.0.0", "APACHE-2.0.txt"),
          legal("org.slf4j:slf4j-api:2.0.18", "SLF4J-LICENSE.txt"),
          legal("org.xerial:sqlite-jdbc:3.53.2.1", "SQLITE-JDBC-APACHE-2.0.txt"));

  private static final Set<String> GRPC_NATIVE_FILES =
      Set.of(
          "META-INF/native/io_grpc_netty_shaded_netty_tcnative_windows_x86_64.dll",
          "META-INF/native/libio_grpc_netty_shaded_netty_tcnative_linux_aarch_64.so",
          "META-INF/native/libio_grpc_netty_shaded_netty_tcnative_linux_x86_64.so",
          "META-INF/native/libio_grpc_netty_shaded_netty_tcnative_osx_aarch_64.jnilib",
          "META-INF/native/libio_grpc_netty_shaded_netty_tcnative_osx_x86_64.jnilib",
          "META-INF/native/libio_grpc_netty_shaded_netty_transport_native_epoll_aarch_64.so",
          "META-INF/native/libio_grpc_netty_shaded_netty_transport_native_epoll_x86_64.so");

  private static final Set<String> JNA_NATIVE_FILES =
      Set.of(
          "com/sun/jna/aix-ppc/libjnidispatch.a",
          "com/sun/jna/aix-ppc64/libjnidispatch.a",
          "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib",
          "com/sun/jna/darwin-x86-64/libjnidispatch.jnilib",
          "com/sun/jna/freebsd-x86-64/libjnidispatch.so",
          "com/sun/jna/freebsd-x86/libjnidispatch.so",
          "com/sun/jna/linux-aarch64/libjnidispatch.so",
          "com/sun/jna/linux-arm/libjnidispatch.so",
          "com/sun/jna/linux-armel/libjnidispatch.so",
          "com/sun/jna/linux-loongarch64/libjnidispatch.so",
          "com/sun/jna/linux-mips64el/libjnidispatch.so",
          "com/sun/jna/linux-ppc/libjnidispatch.so",
          "com/sun/jna/linux-ppc64le/libjnidispatch.so",
          "com/sun/jna/linux-riscv64/libjnidispatch.so",
          "com/sun/jna/linux-s390x/libjnidispatch.so",
          "com/sun/jna/linux-x86-64/libjnidispatch.so",
          "com/sun/jna/linux-x86/libjnidispatch.so",
          "com/sun/jna/openbsd-x86-64/libjnidispatch.so",
          "com/sun/jna/openbsd-x86/libjnidispatch.so",
          "com/sun/jna/sunos-sparc/libjnidispatch.so",
          "com/sun/jna/sunos-sparcv9/libjnidispatch.so",
          "com/sun/jna/sunos-x86-64/libjnidispatch.so",
          "com/sun/jna/sunos-x86/libjnidispatch.so",
          "com/sun/jna/win32-aarch64/jnidispatch.dll",
          "com/sun/jna/win32-x86-64/jnidispatch.dll",
          "com/sun/jna/win32-x86/jnidispatch.dll");

  private static final Set<String> PTY4J_NATIVE_FILES =
      Set.of(
          "resources/com/pty4j/native/darwin/libpty.dylib",
          "resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper",
          "resources/com/pty4j/native/freebsd/x86-64/libpty.so",
          "resources/com/pty4j/native/freebsd/x86/libpty.so",
          "resources/com/pty4j/native/linux/aarch64/libpty.so",
          "resources/com/pty4j/native/linux/arm/libpty.so",
          "resources/com/pty4j/native/linux/mips64el/libpty.so",
          "resources/com/pty4j/native/linux/ppc64le/libpty.so",
          "resources/com/pty4j/native/linux/riscv64/libpty.so",
          "resources/com/pty4j/native/linux/x86-64/libpty.so",
          "resources/com/pty4j/native/linux/x86/libpty.so",
          "resources/com/pty4j/native/win/aarch64/OpenConsole.exe",
          "resources/com/pty4j/native/win/aarch64/conpty.dll",
          "resources/com/pty4j/native/win/aarch64/win-helper.dll",
          "resources/com/pty4j/native/win/aarch64/winpty-agent.exe",
          "resources/com/pty4j/native/win/aarch64/winpty.dll",
          "resources/com/pty4j/native/win/x86-64/OpenConsole.exe",
          "resources/com/pty4j/native/win/x86-64/conpty.dll",
          "resources/com/pty4j/native/win/x86-64/cyglaunch.exe",
          "resources/com/pty4j/native/win/x86-64/win-helper.dll",
          "resources/com/pty4j/native/win/x86-64/winpty-agent.exe",
          "resources/com/pty4j/native/win/x86-64/winpty.dll",
          "resources/com/pty4j/native/win/x86/winpty-agent.exe",
          "resources/com/pty4j/native/win/x86/winpty.dll");

  private static final Set<String> SQLITE_NATIVE_FILES =
      Set.of(
          "org/sqlite/native/FreeBSD/aarch64/libsqlitejdbc.so",
          "org/sqlite/native/FreeBSD/x86/libsqlitejdbc.so",
          "org/sqlite/native/FreeBSD/x86_64/libsqlitejdbc.so",
          "org/sqlite/native/Linux-Musl/aarch64/libsqlitejdbc.so",
          "org/sqlite/native/Linux-Musl/x86/libsqlitejdbc.so",
          "org/sqlite/native/Linux-Musl/x86_64/libsqlitejdbc.so",
          "org/sqlite/native/Linux/aarch64/libsqlitejdbc.so",
          "org/sqlite/native/Linux/arm/libsqlitejdbc.so",
          "org/sqlite/native/Linux/armv6/libsqlitejdbc.so",
          "org/sqlite/native/Linux/armv7/libsqlitejdbc.so",
          "org/sqlite/native/Linux/ppc64/libsqlitejdbc.so",
          "org/sqlite/native/Linux/riscv64/libsqlitejdbc.so",
          "org/sqlite/native/Linux/x86/libsqlitejdbc.so",
          "org/sqlite/native/Linux/x86_64/libsqlitejdbc.so",
          "org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib",
          "org/sqlite/native/Mac/x86_64/libsqlitejdbc.dylib",
          "org/sqlite/native/Windows/aarch64/sqlitejdbc.dll",
          "org/sqlite/native/Windows/armv7/sqlitejdbc.dll",
          "org/sqlite/native/Windows/x86/sqlitejdbc.dll",
          "org/sqlite/native/Windows/x86_64/sqlitejdbc.dll");

  @Test
  @DisplayName("the deploy jar has the complete indexed runtime closure")
  void deployJarCarriesCompleteRuntimeInventory() throws Exception {
    try (ZipFile jar = new ZipFile(deployJar().toFile())) {
      ZipEntry summary = requiredEntry(jar, PREFIX + "THIRD-PARTY-NOTICES.txt");
      String text = new String(readBounded(jar, summary), StandardCharsets.UTF_8);

      assertThat(RUNTIME_ARTIFACTS).hasSize(40);
      assertThat(RUNTIME_ARTIFACTS).extracting(RuntimeArtifact::coordinate).doesNotHaveDuplicates();
      Set<String> expectedCoordinates =
          RUNTIME_ARTIFACTS.stream()
              .map(RuntimeArtifact::coordinate)
              .collect(Collectors.toUnmodifiableSet());
      Set<String> indexedCoordinates =
          Pattern.compile("[a-z][A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
              .matcher(text)
              .results()
              .map(result -> result.group())
              .collect(Collectors.toUnmodifiableSet());
      assertThat(indexedCoordinates).containsExactlyInAnyOrderElementsOf(expectedCoordinates);
      assertThat(RUNTIME_LEGAL_FILES.keySet())
          .containsExactlyInAnyOrderElementsOf(expectedCoordinates);
      for (RuntimeArtifact artifact : RUNTIME_ARTIFACTS) {
        assertThat(text).as(artifact.coordinate() + " notice").contains(artifact.coordinate());
        String legalFile = RUNTIME_LEGAL_FILES.get(artifact.coordinate());
        assertThat(text).as(artifact.coordinate() + " legal mapping").contains(legalFile);
        requiredEntry(jar, PREFIX + legalFile);
        if (artifact.evidenceEntry() != null) {
          assertThat(jar.getEntry(artifact.evidenceEntry()))
              .as(artifact.coordinate() + " packaged evidence")
              .isNotNull();
        }
      }

      assertThat(text)
          .contains("grpc-context is an intentionally empty compatibility jar")
          .contains("Snappy-derived Java implementation")
          .contains("Netty 4.2.15.Final")
          .contains("Netty TCNative BoringSSL Static 2.0.75.Final")
          .contains("BoringSSL commit 0226f30467f540a3f62ef48d453f93927da199b6")
          .contains("libffi 3.4.4")
          .contains("SQLite 3.53.2 native engine")
          .contains("Apache Commons Lang 3.4-derived date formatting classes")
          .contains("Bazel 9.2.0 protocol schemas")
          .contains("c3e3d8a2031ec31f0f81fa42454ba55c7b40f284")
          .contains("ca85771921e4d23ebb56030bf1e488f215f26d36")
          .contains("Material Icon Theme 5.38.1")
          .contains("448ab3977ef83b817c2c722ce7cd5034d195b39f")
          .contains("These notices apply only to their named components");
    }
  }

  @Test
  @DisplayName("the notice inventory equals Bazel's actual Maven runtime closure")
  void noticeInventoryEqualsActualBazelRuntimeClosure() throws Exception {
    String queryOutput = new String(readBounded(runtimeMavenDeps()), StandardCharsets.UTF_8);
    List<String> actualCoordinates =
        Pattern.compile("^[ \\t]*maven_coordinates = \"([^\"]+)\",$", Pattern.MULTILINE)
            .matcher(queryOutput)
            .results()
            .map(result -> result.group(1))
            .toList();
    assertThat(actualCoordinates).hasSize(40).doesNotHaveDuplicates();

    Set<String> expectedCoordinates =
        RUNTIME_ARTIFACTS.stream()
            .map(RuntimeArtifact::coordinate)
            .collect(Collectors.toUnmodifiableSet());
    assertThat(actualCoordinates).containsExactlyInAnyOrderElementsOf(expectedCoordinates);
  }

  @Test
  @DisplayName("the deploy jar retains every exact collision-safe legal file")
  void deployJarCarriesExactLegalPayload() throws Exception {
    try (ZipFile jar = new ZipFile(deployJar().toFile())) {
      Set<String> expectedNames = new HashSet<>(UPSTREAM_LEGAL_FILES.keySet());
      expectedNames.add("THIRD-PARTY-NOTICES.txt");
      List<String> actualNames =
          jar.stream()
              .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(PREFIX))
              .map(entry -> entry.getName().substring(PREFIX.length()))
              .toList();
      assertThat(actualNames)
          .doesNotHaveDuplicates()
          .containsExactlyInAnyOrderElementsOf(expectedNames);

      assertExactFiles(jar, PREFIX, UPSTREAM_LEGAL_FILES);
      assertExactFiles(jar, "", ORIGINAL_ARTIFACT_LEGAL_FILES);
    }
  }

  @Test
  @DisplayName("the deploy jar retains complete reviewed native payloads")
  void deployJarCarriesCompleteNativePayloads() throws Exception {
    try (ZipFile jar = new ZipFile(deployJar().toFile())) {
      assertExactResourceSet(jar, "META-INF/native/", GRPC_NATIVE_FILES);
      assertExactNativeResourceSet(jar, "com/sun/jna/", JNA_NATIVE_FILES);
      assertExactResourceSet(jar, "resources/com/pty4j/native/", PTY4J_NATIVE_FILES);
      assertExactResourceSet(jar, "org/sqlite/native/", SQLITE_NATIVE_FILES);
      assertThat(jar.getEntry("org/sqlite/date/FastDateFormat.class"))
          .as("SQLite JDBC's embedded Commons Lang-derived date formatter")
          .isNotNull();

      String nettyVersion =
          new String(
              readBounded(
                  jar,
                  requiredEntry(jar, "META-INF/io.grpc.netty.shaded.io.netty.versions.properties")),
              StandardCharsets.UTF_8);
      assertThat(nettyVersion)
          .contains("netty-common.version=4.2.15.Final")
          .contains("netty-common.longCommitHash=a41f7b289ce1d697c50846f3ade3983e22b2ed40");
    }
  }

  @Test
  @DisplayName("the deploy jar retains reviewed third-party and project icons")
  void deployJarCarriesRepositoryIconsAndRenderer() throws Exception {
    try (ZipFile jar = new ZipFile(deployJar().toFile())) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String name : MATERIAL_REPOSITORY_ICONS) {
        byte[] bytes = readBounded(jar, requiredEntry(jar, ICON_PREFIX + name));
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(bytes);

        String svg = new String(bytes, StandardCharsets.UTF_8);
        assertThat(svg)
            .as(name + " has no active or external content")
            .doesNotContainPattern("(?i)<script\\b")
            .doesNotContainPattern("(?i)\\b(?:xlink:)?href\\s*=")
            .doesNotContainPattern("(?i)url\\(\\s*['\"]?(?:https?:|//)");
      }
      assertThat(HexFormat.of().formatHex(digest.digest()))
          .as("exact reviewed Material Icon Theme SVG subset")
          .isEqualTo(MATERIAL_REPOSITORY_ICONS_SHA256);

      for (Map.Entry<String, String> expected : PROJECT_REPOSITORY_ICONS.entrySet()) {
        byte[] projectBytes = readBounded(jar, requiredEntry(jar, ICON_PREFIX + expected.getKey()));
        assertThat(sha256(projectBytes))
            .as("exact project SVG " + expected.getKey())
            .isEqualTo(expected.getValue());
        assertThat(new String(projectBytes, StandardCharsets.UTF_8))
            .as(expected.getKey() + " has no active or external content")
            .doesNotContainPattern("(?i)<script\\b")
            .doesNotContainPattern("(?i)\\b(?:xlink:)?href\\s*=")
            .doesNotContainPattern("(?i)url\\(\\s*['\"]?(?:https?:|//)");
      }
    }
  }

  private static RuntimeArtifact artifact(String coordinate, String evidenceEntry) {
    return new RuntimeArtifact(coordinate, evidenceEntry);
  }

  private static Map.Entry<String, String> legal(String coordinate, String legalFile) {
    return Map.entry(coordinate, legalFile);
  }

  private static void assertExactFiles(
      ZipFile jar, String prefix, Map<String, String> expectedFiles) throws Exception {
    for (Map.Entry<String, String> expected : expectedFiles.entrySet()) {
      byte[] bytes = readBounded(jar, requiredEntry(jar, prefix + expected.getKey()));
      assertThat(sha256(bytes))
          .as(expected.getKey() + " exact upstream text")
          .isEqualTo(expected.getValue());
    }
  }

  private static void assertExactResourceSet(
      ZipFile jar, String prefix, Set<String> expectedFiles) {
    Set<String> actualFiles =
        jar.stream()
            .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
            .map(ZipEntry::getName)
            .collect(Collectors.toUnmodifiableSet());
    assertThat(actualFiles).containsExactlyInAnyOrderElementsOf(expectedFiles);
  }

  private static void assertExactNativeResourceSet(
      ZipFile jar, String prefix, Set<String> expectedFiles) {
    Set<String> actualFiles =
        jar.stream()
            .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
            .map(ZipEntry::getName)
            .filter(
                name ->
                    name.endsWith(".a")
                        || name.endsWith(".dll")
                        || name.endsWith(".jnilib")
                        || name.endsWith(".so"))
            .collect(Collectors.toUnmodifiableSet());
    assertThat(actualFiles).containsExactlyInAnyOrderElementsOf(expectedFiles);
  }

  private static ZipEntry requiredEntry(ZipFile jar, String name) {
    ZipEntry entry = jar.getEntry(name);
    assertThat(entry).as(name + " entry").isNotNull();
    return entry;
  }

  private static byte[] readBounded(ZipFile jar, ZipEntry entry) throws Exception {
    assertThat(entry.getSize()).as(entry.getName() + " declared size").isBetween(0L, 262_144L);
    try (InputStream input = jar.getInputStream(entry)) {
      byte[] bytes = input.readNBytes(262_145);
      assertThat(bytes).as(entry.getName() + " bounded content").hasSizeLessThanOrEqualTo(262_144);
      return bytes;
    }
  }

  private static byte[] readBounded(Path path) throws Exception {
    try (InputStream input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(262_145);
      assertThat(bytes).as(path + " bounded content").hasSizeLessThanOrEqualTo(262_144);
      return bytes;
    }
  }

  private static Path deployJar() {
    return Path.of(System.getenv("TEST_SRCDIR"), "_main", "app", "app_deploy.jar");
  }

  private static Path runtimeMavenDeps() {
    return Path.of(System.getenv("TEST_SRCDIR"), "_main", "app", "app_runtime_maven_deps");
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record RuntimeArtifact(String coordinate, String evidenceEntry) {}
}
