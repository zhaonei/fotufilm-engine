#!/usr/bin/env python3
"""Applies our fixes to the Spektrafilm source before it is built.

    python3 spektrafilm-patches/apply.py <path-to-spektrafilm-checkout>

Two defects, both read off the source rather than guessed:

1. Crash on switching editing categories.
   EditorScreen.kt composes the tool sections in a loop with no key:

       categoriesOf(open.stage).filter(::toolShown).forEachIndexed { i, tool ->
           if (i > 0) HorizontalDivider(...)     // a child that only sometimes exists
           ToolHeader(tool = tool, ...)
           toolContent(tool)
       }

   The filter's length changes when categories are switched, and the conditional divider
   changes how many nodes each iteration emits, so Compose matches children by position
   and ends up removing a node it has already removed: the reported
   `Operation$RemoveNode` -> `LayoutNode.onChildRemoved` NPE. A key per tool gives each
   iteration a stable identity and the crash goes away. `key(tool)` is the documented
   remedy for exactly this.

2. Export of a 26 MP frame fails with "not enough memory".
   The engine allocates the full-resolution working set per parallel chunk
   (`spk_set_parallel_chunks_per_worker`, exposed to Kotlin as
   SpektraEngine.setParallelChunksPerWorker). The existing one-shot retry after a GPU
   reclaim re-runs the render at the *same* peak, which is why it fails again. The retry
   now drops to one chunk per worker for its duration (0 restores the default), so the
   second attempt needs a fraction of the memory and finishes somewhat slower instead of
   not at all. `largeHeap` is requested as well, for the Java-side buffers of the output.

Every replacement asserts its anchor. If the upstream file has moved on, the build stops
here with a message rather than shipping an APK that quietly lacks the fix.
"""
import pathlib
import sys

RESOURCE_IMPORT = "import androidx.compose.runtime.key"


def patch_category_keys(root: pathlib.Path) -> None:
    path = root / "app/src/main/java/com/spectrafilm/app/EditorScreen.kt"
    text = path.read_text()

    loop = """categoriesOf(open.stage).filter(::toolShown).forEachIndexed { i, tool ->"""
    if loop not in text:
        raise SystemExit(f"patch 1: anchor missing in {path} (the tool loop moved)")

    # key(tool) { … } around the three statements the loop emits.
    text = text.replace(loop, loop + "\n                                key(tool) {", 1)
    close = """                                toolContent(tool)
                            }"""
    if close not in text:
        raise SystemExit(f"patch 1: the loop's closing brace moved in {path}")
    text = text.replace(close, """                                toolContent(tool)
                                }
                            }""", 1)

    # The file imports androidx.compose.runtime.* , so `key` is already in scope; only add an
    # explicit import when the wildcard is not there.
    if RESOURCE_IMPORT not in text and "import androidx.compose.runtime.*" not in text:
        anchor = "import androidx.compose.runtime.Composable"
        if anchor not in text:
            raise SystemExit(f"patch 1: no runtime import block in {path}")
        text = text.replace(anchor, RESOURCE_IMPORT + "\n" + anchor, 1)

    path.write_text(text)
    print("patch 1: key(tool) around the tool sections -- the category-switch crash")


def patch_export_memory(root: pathlib.Path) -> None:
    path = root / "app/src/main/java/com/spectrafilm/app/GpuMemoryRelease.kt"
    text = path.read_text()

    anchor = """    reclaim()
    return try {
        block()
    } catch (second: Throwable) {
        if (second !== first) second.addSuppressed(first)
        if (isMemoryExhaustion(second)) {
            log("$reason retry also ran out of memory (${second.message}); budget=${snapshot()}")
        }
        throw second
    }
}"""
    if anchor not in text:
        raise SystemExit(f"patch 2: anchor missing in {path} (the retry moved)")

    replacement = """    reclaim()
    // The retry would meet the same peak and fail the same way: the engine holds a
    // full-resolution working set per parallel chunk. One chunk per worker for the retry only
    // (0 restores the default) trades a little speed for fitting in the memory that is actually
    // there -- the difference between "choose a smaller size" and an export.
    SpektraEngine.setParallelChunksPerWorker(1)
    return try {
        block()
    } catch (second: Throwable) {
        if (second !== first) second.addSuppressed(first)
        if (isMemoryExhaustion(second)) {
            log("$reason retry also ran out of memory (${second.message}); budget=${snapshot()}")
        }
        throw second
    } finally {
        SpektraEngine.setParallelChunksPerWorker(0)
    }
}"""
    path.write_text(text.replace(anchor, replacement, 1))
    print("patch 2: one chunk per worker on the out-of-memory retry")


def patch_large_heap(root: pathlib.Path) -> None:
    path = root / "app/src/main/AndroidManifest.xml"
    text = path.read_text()
    if 'android:largeHeap' in text:
        print("patch 3: largeHeap already requested")
        return
    anchor = "<application"
    index = text.index(anchor)
    # Insert as the next attribute of that tag.
    end = text.index(">", index)
    text = text[:end] + '\n        android:largeHeap="true"' + text[end:]
    path.write_text(text)
    print("patch 3: largeHeap for the export path")


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    root = pathlib.Path(argv[1])
    if not (root / "app/src/main/AndroidManifest.xml").is_file():
        raise SystemExit(f"{root} is not a Spektrafilm checkout")
    patch_category_keys(root)
    patch_export_memory(root)
    patch_large_heap(root)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
