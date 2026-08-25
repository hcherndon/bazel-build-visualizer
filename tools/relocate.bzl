# Working around a Bazel limitation that is otherwise fatal to this repo's
# layout: the execroot symlink forest refuses to plant any TOP-LEVEL workspace
# directory whose name starts with the product prefix "bazel-"
# (SymlinkForest.symlinkShouldBePlanted, hardcoded productName + "-"), because
# such names are reserved for the convenience symlinks. This repository has a
# tracked module named `bazel-runner`, so every action that read a source file
# under bazel-runner/ would see a dangling path — in workers, sandboxes and
# local spawns alike.
#
# The escape hatch: template expansion is executed by Bazel IN-PROCESS, which
# reads the input through its source root rather than through the execroot
# forest. Expanding with zero substitutions is a byte-exact copy into
# bazel-out, where the "bazel-" prefix is meaningless. Compile actions then
# consume the copies. Nothing about the module's name, label, or on-disk
# layout changes; only its compile inputs are re-rooted.

def _relocated_srcs_impl(ctx):
    outs = []
    for src in ctx.files.srcs:
        out = ctx.actions.declare_file("_relocated/" + src.short_path)
        ctx.actions.expand_template(
            template = src,
            output = out,
            substitutions = {},
        )
        outs.append(out)
    return [DefaultInfo(files = depset(outs))]

relocated_srcs = rule(
    implementation = _relocated_srcs_impl,
    attrs = {
        "srcs": attr.label_list(
            allow_files = True,
            doc = "Source files to copy into bazel-out, byte for byte.",
        ),
    },
    doc = "Byte-exact in-process copies of source files, placed under " +
          "bazel-out so actions can read them even when the source lives " +
          "in a top-level directory the execroot forest refuses to plant.",
)
