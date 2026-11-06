#!/usr/bin/env python3
"""Fails if a tr() call sits inside a const expression, which Dart will not compile.

    Dart: "Error: Method invocation is not a constant expression."

l10n/zh.dart's tr() is a function, so `const FieldLabel(tr('Name'))`, `const [Text(tr('x'))]` and
`const {'k': tr('x')}` are all illegal. Dart reports one such line per build, which turns a
30-minute CI cycle into a guessing game; this reports every one of them in a second, with no Dart
toolchain and no network.

It reads the file as text, skips string literals and comments, keeps a stack of every bracket it
has open, and remembers for each whether the expression it opens was marked `const`:

    const Column(children: [        <- const group
      Text(tr('Cancel')),           <- violation, through two levels
    ])

    return const [
      Section(title: tr('Normal')),  <- violation through a const list
    ];

Nothing is guessed from line numbers or indentation.

    python3 android-shell/tools/check-l10n-const.py [flutter/lib]
"""
import pathlib
import re
import sys

OPEN = '([{'
CLOSE = ')]}'
MATCH = {')': '(', ']': '[', '}': '{'}
CALL = 'tr('


def scan(text):
    """Yield (index, char, stack) for each character, where stack is the open group list.

    Each stack entry is (bracket, from_index, is_const). Strings and comments are skipped, so a
    bracket inside them cannot open or close anything.
    """
    stack = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c in '\'"':
            quote = c
            i += 1
            while i < n:
                if text[i] == '\\':
                    i += 2
                    continue
                if text[i] == quote:
                    break
                i += 1
            i += 1
            continue
        if c == '/' and i + 1 < n:
            if text[i + 1] == '/':
                while i < n and text[i] != '\n':
                    i += 1
                continue
            if text[i + 1] == '*':
                end = text.find('*/', i + 2)
                i = n if end == -1 else end + 2
                continue
        if c in OPEN:
            stack.append((c, i, opens_const(text, i)))
            yield i, c, tuple(stack)
            i += 1
            continue
        if c in CLOSE:
            if stack and stack[-1][0] == MATCH[c]:
                stack.pop()
            yield i, c, tuple(stack)
            i += 1
            continue
        yield i, c, tuple(stack)
        i += 1


def opens_const(text, index):
    """Whether the expression opened at `index` was written as `const Foo(...)` or `const [...]`."""
    j = index - 1
    while j >= 0 and text[j] in ' \t\n\r':
        j -= 1
    if j >= 0 and text[j] == '>':                       # const Foo<Bar>(...)
        depth = 0
        while j >= 0:
            if text[j] == '>':
                depth += 1
            elif text[j] == '<':
                depth -= 1
                if depth == 0:
                    j -= 1
                    break
            j -= 1
        while j >= 0 and text[j] in ' \t\n\r':
            j -= 1
    end = j + 1
    while j >= 0 and (text[j].isalnum() or text[j] in '_.$'):
        j -= 1
    word = text[j + 1:end]
    if word == 'const':                     # `const [` / `const {...}` : no type name at all
        return True
    before = text[:j + 1]
    if word:                                # `const Column(...)` : look past the type name
        return re.search(r'const\s*$', before) is not None
    return re.search(r'const\s*$', text[:index]) is not None


def violations(text):
    """(offset, reason) for every tr() call inside a const group."""
    found = []
    for index, char, stack in scan(text):
        if char == 't' and text.startswith(CALL, index):
            const_groups = [g for g in stack if g[2]]
            if const_groups:
                opener = const_groups[-1][1]
                found.append((index, text.count('\n', 0, opener) + 1))
    return found


def main(argv):
    root = pathlib.Path(argv[1] if len(argv) > 1 else 'flutter/lib')
    if not root.is_dir():
        print(f'no such directory: {root}', file=sys.stderr)
        return 2
    total = 0
    for path in sorted(root.rglob('*.dart')):
        text = path.read_text()
        for offset, opener_line in violations(text):
            line = text.count('\n', 0, offset) + 1
            snippet = text[text.rfind('\n', 0, offset) + 1:
                           text.find('\n', offset) if text.find('\n', offset) != -1 else len(text)]
            print(f'{path}:{line}: tr() inside a const expression opened at line {opener_line}: '
                  f'{snippet.strip()[:80]}')
            total += 1
    if total:
        print(f'\n{total} tr() call(s) inside a const expression. Remove the const, or the call.')
        return 1
    print(f'checked {root}: every tr() call is outside a const expression')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
