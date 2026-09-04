package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Keeps reviewed licenses and notices from disappearing when dependency jars are merged. */
final class ThirdPartyNoticesTest {

  private static final String PREFIX = "META-INF/third-party/";
  private static final String ICON_PREFIX = "com/holtherndon/bazelviz/ui/repository/icons/";
  private static final String JCTOOLS_PREFIX =
      "io/grpc/netty/shaded/io/netty/util/internal/shaded/org/jctools/";
  private static final String JSVG_SOURCE_ARCHIVE = "JSVG-2.1.0-CORRESPONDING-SOURCE.tar.gz";
  private static final String JSVG_SOURCE_ARCHIVE_SHA256 =
      "10d0c55cbfe150cf6dc4b0856cabc974a7c33f0c068383642dbeb0285d8125b0";

  private static final Set<String> JSVG_REQUIRED_SOURCE_ENTRIES =
      Set.of(
          "jsvg-2.1.0/build.gradle.kts",
          "jsvg-2.1.0/settings.gradle.kts",
          "jsvg-2.1.0/gradlew",
          "jsvg-2.1.0/gradle/wrapper/gradle-wrapper.jar",
          "jsvg-2.1.0/jsvg/src/main/java/com/github/weisj/jsvg/paint/impl/jdk/SVGMultipleGradientPaint.java",
          "jsvg-2.1.0/jsvg/src/main/java/com/github/weisj/jsvg/paint/impl/jdk/SVGMultipleGradientPaintContext.java",
          "jsvg-2.1.0/jsvg/src/main/java/com/github/weisj/jsvg/paint/impl/jdk/SVGRadialGradientPaint.java",
          "jsvg-2.1.0/jsvg/src/main/java/com/github/weisj/jsvg/paint/impl/jdk/SVGRadialGradientPaintContext.java");

  private static final Map<String, String> NETTY_EMBEDDED_EVIDENCE =
      Map.ofEntries(
          Map.entry(
              "JSR-166 concurrency code",
              "io/grpc/netty/shaded/io/netty/util/concurrent/ConcurrentSkipListIntObjMultimap.class"),
          Map.entry(
              "Robert Harder Base64 code",
              "io/grpc/netty/shaded/io/netty/handler/codec/base64/Base64.class"),
          Map.entry(
              "Webbit WebSocket code",
              "io/grpc/netty/shaded/io/netty/handler/codec/http/websocketx/WebSocket08FrameEncoder.class"),
          Map.entry(
              "SLF4J message formatting code",
              "io/grpc/netty/shaded/io/netty/util/internal/logging/MessageFormatter.class"),
          Map.entry(
              "Apache Harmony networking code", "io/grpc/netty/shaded/io/netty/util/NetUtil.class"),
          Map.entry(
              "jbzip2 code",
              "io/grpc/netty/shaded/io/netty/handler/codec/compression/Bzip2Decoder.class"),
          Map.entry(
              "libdivsufsort code",
              "io/grpc/netty/shaded/io/netty/handler/codec/compression/Bzip2DivSufSort.class"),
          Map.entry(
              "jfastlz code",
              "io/grpc/netty/shaded/io/netty/handler/codec/compression/FastLz.class"),
          Map.entry(
              "HPACK code", "io/grpc/netty/shaded/io/netty/handler/codec/http2/HpackDecoder.class"),
          Map.entry(
              "Apache Commons Lang code",
              "io/grpc/netty/shaded/io/netty/util/internal/StringUtil.class"));

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
              "FLATLAF-APPLE-JNFRUNLOOP.h",
              "0b44c1e1eded2d7614280e2811794d7dcbce991192d3e390622846ecf0dd3ace"),
          Map.entry(
              "FLATLAF-INTELLIJ-APACHE-2.0.txt",
              "7dea75e38a7369ab41879858ffbad944914300f468dad80cd84b5e3d9baaf0e0"),
          Map.entry(
              "FLATLAF-INTELLIJ-NOTICE.txt",
              "83903abdebe3b3dda1a178fb3f03ac71ef31c4503b79226b66ef27f1aa9d3993"),
          Map.entry(
              "FLATLAF-MINIMAL-JSON-MIT.txt",
              "2c694bf3dc1665bde8f642f3234734762228028ef42cc4ad743acf98c3161e18"),
          Map.entry(
              "FLATLAF-NATIVEFILEDIALOG-EXTENDED-ZLIB.txt",
              "b332d6f507142c66a0e904838b6437e6273734589c22a16b57392b89bfdccb2a"),
          Map.entry(
              "FLATLAF-TIPS4JAVA-HSLCOLOR-NOTICE.txt",
              "9607f4612e40e7d7a881b8ab17795750848513049812206a55dc71f3b49e0236"),
          Map.entry(
              "GRPC-NETTY-SHADED-APACHE-2.0.txt",
              "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
          Map.entry(
              "GRPC-NETTY-SHADED-JCTOOLS-APACHE-2.0.txt",
              "cb5e8e7e5f4a3988e1063c142c60dc2df75605f4c46515e776e3aca6df976e14"),
          Map.entry(
              "GRPC-NETTY-SHADED-NOTICE.txt",
              "45d84f3f695ff7b50308c68727227e57b5e5be6739f9fcfe8b8a21068803da65"),
          Map.entry(
              "GOOGLEAPIS-APACHE-2.0.txt",
              "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
          Map.entry(
              "GRPC-JAVA-NOTICE.txt",
              "d891421cec918666dce9dc4516dad6a76557d12203fec121662efff365e72d63"),
          Map.entry(
              "JEDITERM-APACHE-2.0.txt",
              "2245a990b635558be210fb3eb4f8a6f7a49aebc0fefbf5859146a65ddc7ddcf3"),
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
              "JSVG-ABSTRACT-BLEND-COMPOSITE-BSD-NOTICE.txt",
              "8231ac5c45b63e15dc18c52b3c78bef6cd2c157190c601084329927e1bc2c4d9"),
          Map.entry(
              "JSVG-JDATAURI-ZLIB.txt",
              "41b4e996568e50f71d781d70133fda23428b2f3ea3fb08fd9ea8cfbc22da3717"),
          Map.entry(
              "JSVG-MIT.txt", "4ef80d54216cb7a7063cd8b068b6abc1236d99a416841a81766234846e32f3c5"),
          Map.entry(
              "JSVG-OPENJDK-GPL-2.0-CLASSPATH-EXCEPTION.txt",
              "4b9abebc4338048a7c2dc184e9f800deb349366bdf28eb23c2677a77b4c87726"),
          Map.entry(
              "KOTLIN-BOOST-1.0.txt",
              "8d8291caf1cee26d23acf3eb67c9f9a2d58f1c681b16a4fbe8cbfb9e3c0b5a9b"),
          Map.entry(
              "KOTLIN-LICENSE.txt",
              "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
          Map.entry(
              "KOTLIN-NOTICE.txt",
              "0b09a83d3ef7795c7dec65e815866597c066c53898f2852a76599b8f5552941a"),
          Map.entry(
              "KOTLIN-THREETENBP-BSD-3-CLAUSE.txt",
              "d1bc53b493a3ab387b42717ed5c4b1976a5048996f81154278100bff86d39331"),
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
              "NETTY-AALTO-XML-LICENSE.txt",
              "2dca650805d8e96b8fcd3c2ac8bd17a8813fee479caefce929c75528710f35df"),
          Map.entry(
              "NETTY-APACHE-2.0.txt",
              "aac73b3148f6d1d7111dbca32099f68d26c644c6813ae1e4f05f6579aa2663fe"),
          Map.entry(
              "NETTY-BASE64-LICENSE.txt",
              "922754f715103e9366d1099f85bfea7df192d3d457cd7e222786321c9ab01954"),
          Map.entry(
              "NETTY-BORINGSSL-LICENSE.txt",
              "827c8d8fc207c2392794eef9e00fe246f9f61fdcc132556c275be3dd8c3cd97f"),
          Map.entry(
              "NETTY-BOUNCY-CASTLE-LICENSE.txt",
              "3ad769df48f54692f561debc1be6134efb95b63b6e5031b8167f821bd82a204f"),
          Map.entry(
              "NETTY-BROTLI4J-LICENSE.txt",
              "c1b9df1275e769f3dbab000d1e457a2d4b0f28eb5da6c77e48dc37eeba202ed7"),
          Map.entry(
              "NETTY-CALIPER-LICENSE.txt",
              "aac73b3148f6d1d7111dbca32099f68d26c644c6813ae1e4f05f6579aa2663fe"),
          Map.entry(
              "NETTY-COMMONS-LANG-LICENSE.txt",
              "3e4af21da9cb8a50d6a8f32225d7630fbf94a263e89945489d4863585f4aca4e"),
          Map.entry(
              "NETTY-COMMONS-LOGGING-LICENSE.txt",
              "3e4af21da9cb8a50d6a8f32225d7630fbf94a263e89945489d4863585f4aca4e"),
          Map.entry(
              "NETTY-COMPRESS-LZF-LICENSE.txt",
              "95eab05f6010cc64dedb668523c01fc9f48665cded448de86404b14a7a557ddd"),
          Map.entry(
              "NETTY-DNSINFO-LICENSE.txt",
              "f0bdf5682bf317b1f85c80ed32cb4c7bf77b5a231dee485aa92d983673b7c58f"),
          Map.entry(
              "NETTY-HARMONY-LICENSE.txt",
              "3e4af21da9cb8a50d6a8f32225d7630fbf94a263e89945489d4863585f4aca4e"),
          Map.entry(
              "NETTY-HARMONY-NOTICE.txt",
              "9cea0d3fe0550735946cec78eaf6cfccce153ae06bdcdf17fa3b9f8d9b22196a"),
          Map.entry(
              "NETTY-HPACK-LICENSE.txt",
              "62fb8a3a9621dc2388174caaabe9c2317b694bb9a1d46c98bcf5655b68f51be3"),
          Map.entry(
              "NETTY-HYPER-HPACK-LICENSE.txt",
              "763a9342a04df62046c9dc748a5287934eb0a5331c6863b3ca0aee20e18cb4ed"),
          Map.entry(
              "NETTY-JBOSS-MARSHALLING-LICENSE.txt",
              "ff420781e0005270cd1894a265e526dec4eb332f99df62a481b06672db202290"),
          Map.entry(
              "NETTY-JBZIP2-LICENSE.txt",
              "77edfb9953d4a58f573c40280f9703df301b1f28e68b556914a561b03559c5e9"),
          Map.entry(
              "NETTY-JCTOOLS-LICENSE.txt",
              "3e4af21da9cb8a50d6a8f32225d7630fbf94a263e89945489d4863585f4aca4e"),
          Map.entry(
              "NETTY-JFASTLZ-LICENSE.txt",
              "06c1bf4cd5304c64bb72a6e03b461c389c35af26d623e3add7d828c398482e1d"),
          Map.entry(
              "NETTY-JSR166Y-LICENSE.txt",
              "17869cb6184447de9663a752a7663c118a9ed0d4ce0a2208986afc75206e1bbb"),
          Map.entry(
              "NETTY-JZLIB-LICENSE.txt",
              "2ce9cdc96c015e5bb03fb1b565f33ffc7590c35ff2a33abe923e299135c01ce1"),
          Map.entry(
              "NETTY-LIBDIVSUFSORT-LICENSE.txt",
              "a801f489b279d91b8afc67d41e3769ec4df476fda218d63f4326c005db881541"),
          Map.entry(
              "NETTY-LOG4J-LICENSE.txt",
              "3e4af21da9cb8a50d6a8f32225d7630fbf94a263e89945489d4863585f4aca4e"),
          Map.entry(
              "NETTY-LZ4-LICENSE.txt",
              "aac73b3148f6d1d7111dbca32099f68d26c644c6813ae1e4f05f6579aa2663fe"),
          Map.entry(
              "NETTY-LZMA-JAVA-LICENSE.txt",
              "aac73b3148f6d1d7111dbca32099f68d26c644c6813ae1e4f05f6579aa2663fe"),
          Map.entry(
              "NETTY-MAVEN-WRAPPER-LICENSE.txt",
              "aac73b3148f6d1d7111dbca32099f68d26c644c6813ae1e4f05f6579aa2663fe"),
          Map.entry(
              "NETTY-NGHTTP2-HPACK-LICENSE.txt",
              "6b94f3abc1aabd0c72a7c7d92a77f79dda7c8a0cb3df839a97890b4116a2de2a"),
          Map.entry(
              "NETTY-NOTICE.txt",
              "1775b0d91666fcb82493a5198f761b59a6d4d050bae6a4d26ecc377354068571"),
          Map.entry(
              "NETTY-PROTOBUF-LICENSE.txt",
              "8fa16eae6ec99dfa982ab552ecbf9e98421bf5062356538cac4cd0c7bacd0093"),
          Map.entry(
              "NETTY-QUICHE-LICENSE.txt",
              "2ef4b5abfce387a83933bda738e72467a79d15c1c17679143ec55011dae66b84"),
          Map.entry(
              "NETTY-SLF4J-LICENSE.txt",
              "e45deaad2824e38410545e2ed8ced91eccd13bc03b41643a4bd79c96e106e106"),
          Map.entry(
              "NETTY-SNAPPY-LICENSE.txt",
              "38139f9e51ce002fccec3b6cf2011a24e18e5096b8e6ad715f718828fa0b57d1"),
          Map.entry(
              "NETTY-WEBBIT-LICENSE.txt",
              "9a746c7a218e6ee1ef719a39a0a66454c82ada93c60f06f9f819b505f6401046"),
          Map.entry(
              "NETTY-ZSTD-JNI-LICENSE.txt",
              "101b8b218e3784b326bcbf7fb4a6b0138f63e8df53242d9b10eb80a0056a1133"),
          Map.entry(
              "PERFMARK-APACHE-2.0.txt",
              "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
          Map.entry(
              "PERFMARK-NOTICE.txt",
              "7fd15f7217ae4bb4f9b7000b709dbe05f90caf2387c707bfe3a1840e0fc11a4c"),
          Map.entry(
              "PPROF-APACHE-2.0.txt",
              "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
          Map.entry(
              "PROTOBUF-BSD-3-CLAUSE.txt",
              "6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d"),
          Map.entry(
              "PTY4J-EPL-1.0.txt",
              "3a4a6fe55f67f02b92be0ec9907e347de8ab5c8edf9214c5c8019e5820517884"),
          Map.entry(
              "PTY4J-NOTICE.txt",
              "7f1b01b809cecc274e9a0aaeba288ccb36a18cb96a97b8cc1fde58c1e713e845"),
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
              "SQL-FORMATTER-ZEROTURNAROUND-MIT.txt",
              "d15f5d4a914b854e095ae3c4b2e5d03eface4b0d43a1bda89edfab93b89586d9"),
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
              "SQLITE-PUBLIC-DOMAIN-DECLARATION.html.in",
              "5c22c2aa28ec06d68f6cb1c911c02076841dafea7607577dcd41aa77e4227dc4"),
          Map.entry(
              "TOMCAT-NATIVE-APACHE-2.0.txt",
              "43070e2d4e532684de521b885f385d0841030efa2b1a20bafb76133a5e1379c1"),
          Map.entry(
              "WINDOWS-TERMINAL-LICENSE.txt",
              "5d177f23ecfeb0ea8e050b6a5a16355e1ae9a0b286436ca8f83ed08b3795be6b"),
          Map.entry(
              "WINDOWS-TERMINAL-NOTICE.md",
              "f2a21ffa8034b40efd99188309053e16a9497342bf96515127e099959043b700"),
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

  private static final Set<String> FLATLAF_NATIVE_FILES =
      Set.of(
          "com/formdev/flatlaf/natives/flatlaf-windows-arm64.dll",
          "com/formdev/flatlaf/natives/flatlaf-windows-x86.dll",
          "com/formdev/flatlaf/natives/flatlaf-windows-x86_64.dll",
          "com/formdev/flatlaf/natives/libflatlaf-linux-arm64.so",
          "com/formdev/flatlaf/natives/libflatlaf-linux-x86_64.so",
          "com/formdev/flatlaf/natives/libflatlaf-macos-arm64.dylib",
          "com/formdev/flatlaf/natives/libflatlaf-macos-x86_64.dylib");

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
          .contains("Apache Hadoop/Apache Ant-derived BZip2 implementation")
          .contains("Keiron Liddle and Aftex Software")
          .contains("ZeroTurnaround SQL Formatter 2.3.2")
          .contains("minimal-json 0.9.5-derived parser")
          .contains("Apple OpenJDK JNFRunLoop")
          .contains("nativefiledialog-extended GTK window-handle routine")
          .contains("OpenJDK Java2D gradient-paint code")
          .contains("GPL corresponding source")
          .contains("Sun/Romain Guy AbstractBlendComposite")
          .contains("jDataUri 1.2.1-derived DataUri")
          .contains("GRPC-JAVA-NOTICE.txt")
          .contains("PERFMARK-NOTICE.txt")
          .contains("Netty 4.2.15.Final")
          .contains("JCTools Core 4.0.6")
          .contains("Netty TCNative BoringSSL Static 2.0.75.Final")
          .contains("BoringSSL commit 0226f30467f540a3f62ef48d453f93927da199b6")
          .contains("libffi 3.4.4")
          .contains("Google Web Toolkit-derived collections code")
          .contains("ThreeTenBP-derived time code")
          .contains("Guava-derived unsigned arithmetic code")
          .contains("Boost-derived JVM math code")
          .contains("SQLite 3.53.2 native engine")
          .contains("SQLite docsrc artifact: b32cb1a1fab81560")
          .contains("Apache Commons Lang 3.4-derived date formatting classes")
          .contains("Bazel 9.2.0 protocol schemas")
          .contains("c3e3d8a2031ec31f0f81fa42454ba55c7b40f284")
          .contains("ca85771921e4d23ebb56030bf1e488f215f26d36")
          .contains("Material Icon Theme 5.38.1")
          .contains("448ab3977ef83b817c2c722ce7cd5034d195b39f")
          .contains("These notices apply only to their named components");

      assertThat(jar.getEntry("io/airlift/compress/bzip2/BZip2Constants.class"))
          .as("Aircompressor's Hadoop/Ant-derived BZip2 implementation")
          .isNotNull();
      assertThat(jar.getEntry("com/github/vertical_blank/sqlformatter/SqlFormatter.class"))
          .as("the Java port of ZeroTurnaround SQL Formatter")
          .isNotNull();

      for (String minimalJsonClass :
          List.of(
              "JsonParser.class", "Location.class", "ParseException.class", "JsonHandler.class")) {
        assertThat(jar.getEntry("com/formdev/flatlaf/json/" + minimalJsonClass))
            .as("FlatLaf's minimal-json-derived " + minimalJsonClass)
            .isNotNull();
      }
      assertThat(jar.getEntry("com/formdev/flatlaf/util/GrayFilter.class"))
          .as("FlatLaf's IntelliJ-derived GrayFilter")
          .isNotNull();
      for (String themeProperties :
          List.of(
              "FlatDarkLaf.properties",
              "FlatLightLaf.properties",
              "FlatDarculaLaf.properties",
              "FlatIntelliJLaf.properties")) {
        assertThat(jar.getEntry("com/formdev/flatlaf/" + themeProperties))
            .as("FlatLaf's IntelliJ-derived " + themeProperties)
            .isNotNull();
      }
      assertThat(jar.getEntry("com/formdev/flatlaf/util/HSLColor.class"))
          .as("FlatLaf's Tips4Java-derived HSLColor")
          .isNotNull();

      for (String gradientClass :
          List.of(
              "SVGMultipleGradientPaint.class",
              "SVGMultipleGradientPaintContext.class",
              "SVGRadialGradientPaint.class",
              "SVGRadialGradientPaintContext.class")) {
        assertThat(jar.getEntry("com/github/weisj/jsvg/paint/impl/jdk/" + gradientClass))
            .as("JSVG's GPL-2.0-with-Classpath-Exception " + gradientClass)
            .isNotNull();
      }
      assertThat(jar.getEntry("com/github/weisj/jsvg/nodes/filter/AbstractBlendComposite.class"))
          .as("JSVG's BSD-selected Sun/Romain Guy blend implementation")
          .isNotNull();
      assertThat(jar.getEntry("com/github/weisj/jsvg/util/DataUri.class"))
          .as("JSVG's jDataUri-derived implementation")
          .isNotNull();

      assertThat(jar.getEntry("kotlin/collections/AbstractList.class"))
          .as("Kotlin's GWT-derived collections implementation")
          .isNotNull();
      assertThat(jar.getEntry("kotlin/time/Duration.class"))
          .as("Kotlin's ThreeTenBP-derived time implementation")
          .isNotNull();
      assertThat(jar.getEntry("kotlin/UnsignedKt.class"))
          .as("Kotlin's Guava-derived unsigned implementation")
          .isNotNull();
      assertThat(jar.getEntry("kotlin/math/MathKt__MathJVMKt.class"))
          .as("Kotlin's Boost-derived JVM math implementation")
          .isNotNull();
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
      expectedNames.add(JSVG_SOURCE_ARCHIVE);
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

      String index =
          new String(
              readBounded(jar, requiredEntry(jar, PREFIX + "THIRD-PARTY-NOTICES.txt")),
              StandardCharsets.UTF_8);
      for (String legalFile : UPSTREAM_LEGAL_FILES.keySet()) {
        assertThat(index).as(legalFile + " index mapping").contains(legalFile);
      }
      assertThat(index).contains(JSVG_SOURCE_ARCHIVE);

      byte[] sourceArchive =
          readBounded(jar, requiredEntry(jar, PREFIX + JSVG_SOURCE_ARCHIVE), 500_000);
      assertThat(sha256(sourceArchive))
          .as("exact official JSVG v2.1.0 corresponding-source archive")
          .isEqualTo(JSVG_SOURCE_ARCHIVE_SHA256);
      assertThat(gzipTarEntryNames(sourceArchive))
          .as("GPL-derived JSVG sources and their build scripts")
          .containsAll(JSVG_REQUIRED_SOURCE_ENTRIES);
    }
  }

  @Test
  @DisplayName("the deploy jar retains complete reviewed native payloads")
  void deployJarCarriesCompleteNativePayloads() throws Exception {
    try (ZipFile jar = new ZipFile(deployJar().toFile())) {
      assertExactResourceSet(jar, "META-INF/native/", GRPC_NATIVE_FILES);
      assertExactResourceSet(jar, "com/formdev/flatlaf/natives/", FLATLAF_NATIVE_FILES);
      assertExactNativeResourceSet(jar, "com/sun/jna/", JNA_NATIVE_FILES);
      assertExactResourceSet(jar, "resources/com/pty4j/native/", PTY4J_NATIVE_FILES);
      assertExactResourceSet(jar, "org/sqlite/native/", SQLITE_NATIVE_FILES);
      assertThat(jar.getEntry("org/sqlite/date/FastDateFormat.class"))
          .as("SQLite JDBC's embedded Commons Lang-derived date formatter")
          .isNotNull();

      List<String> jctoolsEntries =
          jar.stream()
              .map(ZipEntry::getName)
              .filter(name -> name.startsWith(JCTOOLS_PREFIX))
              .toList();
      assertThat(jctoolsEntries)
          .as("Netty's complete minimized and relocated JCTools 4.0.6 payload")
          .hasSize(136)
          .doesNotHaveDuplicates()
          .contains(
              JCTOOLS_PREFIX + "queues/MpscArrayQueue.class",
              JCTOOLS_PREFIX + "util/UnsafeAccess.class");

      for (Map.Entry<String, String> evidence : NETTY_EMBEDDED_EVIDENCE.entrySet()) {
        assertThat(jar.getEntry(evidence.getValue()))
            .as("Netty embedded " + evidence.getKey())
            .isNotNull();
      }

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
    return readBounded(jar, entry, 262_144);
  }

  private static byte[] readBounded(ZipFile jar, ZipEntry entry, int maxBytes) throws Exception {
    assertThat(entry.getSize())
        .as(entry.getName() + " declared size")
        .isBetween(0L, (long) maxBytes);
    try (InputStream input = jar.getInputStream(entry)) {
      byte[] bytes = input.readNBytes(maxBytes + 1);
      assertThat(bytes).as(entry.getName() + " bounded content").hasSizeLessThanOrEqualTo(maxBytes);
      return bytes;
    }
  }

  private static Set<String> gzipTarEntryNames(byte[] compressedTar) throws Exception {
    byte[] tar;
    try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressedTar))) {
      tar = input.readNBytes(4_000_001);
    }
    assertThat(tar).as("bounded expanded JSVG source archive").hasSizeLessThanOrEqualTo(4_000_000);

    Set<String> names = new HashSet<>();
    int offset = 0;
    while (offset + 512 <= tar.length && !isZeroBlock(tar, offset)) {
      String name = tarString(tar, offset, 100);
      String prefix = tarString(tar, offset + 345, 155);
      if (!prefix.isEmpty()) {
        name = prefix + "/" + name;
      }
      names.add(name);

      String octalSize = tarString(tar, offset + 124, 12).trim();
      long size = octalSize.isEmpty() ? 0 : Long.parseLong(octalSize, 8);
      assertThat(size).as(name + " tar entry size").isBetween(0L, 4_000_000L);
      long nextOffset = offset + 512L + ((size + 511L) / 512L) * 512L;
      assertThat(nextOffset).as(name + " bounded tar offset").isLessThanOrEqualTo(tar.length);
      offset = Math.toIntExact(nextOffset);
    }
    return names;
  }

  private static boolean isZeroBlock(byte[] bytes, int offset) {
    for (int index = offset; index < offset + 512; index++) {
      if (bytes[index] != 0) {
        return false;
      }
    }
    return true;
  }

  private static String tarString(byte[] bytes, int offset, int length) {
    int end = offset;
    while (end < offset + length && bytes[end] != 0) {
      end++;
    }
    return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
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
