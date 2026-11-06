#!/usr/bin/env python3
"""Fails if a Kotlin file in the Android host refers to a class that does not exist.

A dangling `ExportFiles(this, messenger)` cost a full pipeline run: the engine and
Halide were built, the Flutter bundle was compiled, and only then did
`:app:compileReleaseKotlin` say

    MainActivity.kt:11:30 Unresolved reference 'ExportFiles'.

Dart errors are caught earlier by the Flutter build; a Kotlin one is caught last.
This looks for them in a second, without a Kotlin compiler: every `Name(...)`
constructor call in the app's own package must be a class declared in that
package, an import, or one of the Kotlin/Android types that need no import.

    python3 android-shell/tools/check-kotlin-refs.py [package-dir]
"""
import pathlib
import re
import sys

DECL = re.compile(
    r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:public\s+|internal\s+|private\s+|open\s+|abstract\s+|sealed\s+|data\s+|value\s+|fun\s+)*'
    r'(?:class|interface|object|typealias)\s+([A-Za-z_]\w*)', re.M)
# `private const val ALBUM = "Fotufilm"` is a declaration too, and it is capitalised.
CONST = re.compile(r'^\s*(?:private\s+|internal\s+|public\s+)?const\s+val\s+([A-Za-z_]\w*)', re.M)
IMPORT = re.compile(r'^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?', re.M)
# A capitalised name that is not part of a longer path: `ExportFiles(...)` and `ExportFiles?`
# are references, `java.io.IOException(...)` and `MediaStore.Images` are not.
NAME = re.compile(r'(?<![\w.])([A-Z][A-Za-z0-9_]*)')

# Types that need no import in Kotlin, plus the Android and Java ones this app imports per file.
BUILTIN = {
    # Kotlin stdlib, auto-imported
    'String', 'Char', 'Boolean', 'Int', 'Long', 'Short', 'Byte', 'Float', 'Double', 'Number',
    'Any', 'Unit', 'Nothing', 'List', 'MutableList', 'Map', 'MutableMap', 'Set', 'MutableSet',
    'Iterable', 'Collection', 'Pair', 'Triple', 'Array', 'Sequence', 'Comparable', 'Throwable',
    'Exception', 'RuntimeException', 'IllegalArgumentException', 'IllegalStateException',
    'StringBuilder', 'Result', 'Lazy', 'Regex', 'Function', 'Enum', 'Annotation',
    'Deprecated', 'Suppress', 'JvmStatic', 'JvmField', 'JvmName', 'OptIn', 'UnsafeVariance',
    # Java and Android, used with a class literal or a static call
    'System', 'Math', 'Thread', 'Runnable', 'Integer', 'Long', 'Boolean', 'Character',
    'Override', 'Deprecated', 'IOException', 'Closeable', 'AutoCloseable', 'Comparable',
}


def scan(root):
    files = sorted(root.rglob('*.kt'))
    declared, imported = set(), set()
    for path in files:
        text = path.read_text()
        declared |= set(DECL.findall(text)) | set(CONST.findall(text))
        for whole, alias in IMPORT.findall(text):
            imported.add(alias or whole.rsplit('.', 1)[-1])

    problems = []
    for path in files:
        text = path.read_text()
        # Nothing inside comments or strings is a call.
        text = re.sub(r'//[^\n]*', '', text)
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
        text = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', text)
        for name in sorted(set(NAME.findall(text))):
            if name in declared or name in imported or name in BUILTIN:
                continue
            problems.append((path, name))
    return files, problems


def main(argv):
    root = pathlib.Path(argv[1] if len(argv) > 1 else 'flutter/android/app/src/main/kotlin')
    if not root.is_dir():
        print(f'no such directory: {root}', file=sys.stderr)
        return 2
    files, problems = scan(root)
    for path, name in problems:
        print(f'{path}: refers to {name}, which this package neither declares nor imports')
    if problems:
        print(f'\n{len(problems)} unresolved reference(s) in {len(files)} Kotlin file(s).')
        return 1
    print(f'checked {len(files)} Kotlin file(s): every constructor call resolves')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
