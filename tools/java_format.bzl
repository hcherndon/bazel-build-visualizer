"""Repository-local Google Java Format enforcement."""

def _java_format_aspect_impl(target, ctx):
    if ctx.label.workspace_name or not hasattr(ctx.rule.files, "srcs"):
        return []

    sources = [source for source in ctx.rule.files.srcs if source.is_source and source.extension == "java"]
    if not sources:
        return []

    marker = ctx.actions.declare_file(ctx.label.name + ".google-java-format.ok")
    args = ctx.actions.args()
    args.add("--check-action")
    args.add(marker)
    args.add_all(sources)
    ctx.actions.run(
        executable = ctx.executable._formatter,
        arguments = [args],
        inputs = sources,
        mnemonic = "GoogleJavaFormat",
        outputs = [marker],
        progress_message = "Checking Java format for %{label}",
        tools = [ctx.attr._formatter[DefaultInfo].files_to_run],
    )
    return [OutputGroupInfo(java_format_checks = depset([marker]))]

java_format_aspect = aspect(
    implementation = _java_format_aspect_impl,
    attrs = {
        "_formatter": attr.label(
            default = "//tools/java_quality:format_java",
            cfg = "exec",
            executable = True,
        ),
    },
)
