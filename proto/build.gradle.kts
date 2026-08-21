import com.google.protobuf.gradle.id

plugins {
    id("bbv.java-library")
    alias(libs.plugins.protobuf)
}

// Vendored proto sources under src/main/proto mirror their upstream repo
// layouts (Bazel repo root and googleapis root) so that the repo-relative
// import statements inside the files resolve unchanged. Provenance and pins
// are documented in PROTO_SOURCES.md; refresh via update-protos.sh.

dependencies {
    api(libs.protobuf.java)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.api)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                id("grpc") { }
            }
            // Descriptor sets (with imports) let downstream tooling reflect
            // over the full BEP schema without re-running protoc.
            generateDescriptorSet = true
            descriptorSetOptions.includeImports = true
        }
    }
}
