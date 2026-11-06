#!/usr/bin/env python3
"""Fails if a tr() call sits inside a const expression, which Dart will not compile.

    Android Studio: "Method invocation is not a constant expression."
    Dart VM:        "Error: Method invocation is not a constant expression."

l10n/zh.dart's tr() is a function, so `const FieldLabel(tr('Name'))` is not a constant and
`const [Text(tr('Cancel'))]` is not a constant list. Dart reports the first such line only, which
makes it a compile-run-fix-repeat loop; this finds them all, in a second, without a Dart toolchain.

Two shapes are checked:

  * the `const` is on the same line, before the call -- `const FieldLabel(tr('Name')),`
  * the `const` opens a constructor or list whose body contains the call --
        const Column(children: [
          Text(tr('Cancel')),
        ])

    python3 android-shell/tools/check-l10n-const.py [flutter/lib]
"""
import pathlib
import re
import sys

CALL = 'tr('
CONST = re.compile(r'\bconst\b')


def same_line(text):
    """(line number, line) where tr( shares its line with a const before it."""
    found = []
    for number, line in enumerate(text.split('\n'), start=1):
        index = line.find(CALL)
        while index != -1:
            if CONST.search(line[:index]):
                found.append((number, line.strip()))
                break
            index = line.find(CALL, index + 1)
    return found


def enclosing(text):
    """(line number, opening line) where tr( is inside a const that opened earlier."""
    found, lines = [], text.split('\n')
    for index, line in enumerate(lines):
        if CALL not in line:
            continue
        # The same line was checked already; walk back for the constructor that contains it.
        depth, back = 0, index
        while back > 0:
            probe = lines[back - 1]
            depth += probe.count(')') - probe.count('(')
            if depth < 0:
                if CONST.search(probe):
                    found.append((index + 1, probe.strip()))
                break
            back -= 1
    return found


def main(argv):
    root = pathlib.Path(argv[1] if len(argv) > 1 else 'flutter/lib')
    if not root.is_dir():
        print(f'no such directory: {root}', file=sys.stderr)
        return 2
    total = 0
    for path in sorted(root.rglob('*.dart')):
        if 'l10n' in path.parts:
            continue            # the table itself mentions no tr() in a const
        text = path.read_text()
        for number, line in same_line(text) + enclosing(text):
            print(f'{path}:{number}: tr() is in a const expression: {line}')
            total += 1
    if total:
        print(f'\n{total} tr() call(s) inside a const expression. Remove the const, or the call.')
        return 1
    print(f'checked {root}: every tr() call is outside a const expression')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
