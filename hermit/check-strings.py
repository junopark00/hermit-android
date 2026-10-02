"""Checks format strings and how the Java code uses them, for crashes no compiler catches.

- Every translation of a string has the same placeholders as the default (English) one, for the
  strings this app ships (main + nonRoot; the nonRoot flavour wins). A Korean %1$d where English
  has %1$s throws IllegalFormatConversionException in Korean only.
- getString(R.string.x, args...) / getResources().getString(...) / String.format(...getString(x),
  ...) pass as many arguments as the string has placeholders, and an obviously textual argument
  (a string literal, a getString(...) call, .name, .toString()) never goes to %d.
- A string with placeholders that is shown without arguments is reported as a warning (it shows
  "%1$s" literally rather than crashing).

Usage: python hermit/check-strings.py   (exit code 1 on an error)
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
SRC = os.path.join(ROOT, 'app', 'src')
# The flavour built and released (its resources override main)
FLAVOURS = ['main', 'nonRoot']

PLACEHOLDER = re.compile(r'%(?:(\d+)\$)?([-#+ 0,(]*\d*(?:\.\d+)?)([sdfxXcboeEgGh%n])')


def placeholders(text):
    """Set of (position, conversion) for the format specifiers in a resource string."""
    found = set()
    implicit = 0
    for m in PLACEHOLDER.finditer(text or ''):
        conv = m.group(3)
        if conv in '%n':
            continue
        if m.group(1):
            pos = int(m.group(1))
        else:
            implicit += 1
            pos = implicit
        found.add((pos, conv))
    return found


def load_strings():
    """lang ('' = default) -> name -> (text, formatted flag, file)."""
    langs = {}
    for flavour in FLAVOURS:
        res = os.path.join(SRC, flavour, 'res')
        if not os.path.isdir(res):
            continue
        for d in sorted(os.listdir(res)):
            if not d.startswith('values'):
                continue
            qual = d[len('values'):].lstrip('-')
            lang = qual if re.fullmatch(r'[a-z]{2}(-r[A-Z]{2})?', qual) else ('' if qual == '' else None)
            if lang is None:
                continue
            for f in os.listdir(os.path.join(res, d)):
                if not f.endswith('.xml'):
                    continue
                path = os.path.join(res, d, f)
                try:
                    root = ET.parse(path).getroot()
                except ET.ParseError:
                    continue
                for el in root.findall('string'):
                    text = ''.join(el.itertext())
                    langs.setdefault(lang, {})[el.get('name')] = (text, el.get('formatted') != 'false', path)
    return langs


ARG_SPLIT = re.compile(r',(?![^()]*\))')


def split_args(s):
    """Split a Java argument list at top-level commas."""
    args, depth, cur, quote = [], 0, '', None
    for ch in s:
        if quote:
            cur += ch
            if ch == quote and not cur.endswith('\\' + quote):
                quote = None
            continue
        if ch in '"\'':
            quote = ch
            cur += ch
        elif ch in '([{':
            depth += 1
            cur += ch
        elif ch in ')]}':
            depth -= 1
            cur += ch
        elif ch == ',' and depth == 0:
            args.append(cur.strip())
            cur = ''
        else:
            cur += ch
    if cur.strip():
        args.append(cur.strip())
    return args


def call_args(text, start):
    """The argument text of the call whose '(' is at start."""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == '(':
            depth += 1
        elif text[i] == ')':
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return None


TEXTUAL = re.compile(r'^(?:".*"|.*\bgetString\(.*\)|.*\.name|.*\.toString\(\)|.*\.getName\(\)|.*\.getAppName\(\))$', re.S)


def main():
    langs = load_strings()
    default = langs.get('', {})
    errors, warnings = [], []

    for lang, strings in sorted(langs.items()):
        if lang == '':
            continue
        for name, (text, formatted, path) in strings.items():
            if name not in default or not formatted:
                continue
            want = placeholders(default[name][0])
            got = placeholders(text)
            if want != got:
                errors.append('%s: "%s" has %s, the default has %s' % (
                    os.path.relpath(path, ROOT), name, sorted(got) or 'no placeholders', sorted(want) or 'none'))

    calls = 0
    for base, _, files in os.walk(SRC):
        for f in files:
            if not f.endswith('.java'):
                continue
            path = os.path.join(base, f)
            text = open(path, encoding='utf-8', errors='replace').read()
            for m in re.finditer(r'\b(getString|String\.format)\s*\(', text):
                args_text = call_args(text, m.end() - 1)
                if args_text is None:
                    continue
                args = split_args(args_text)
                if m.group(1) == 'String.format':
                    if not args:
                        continue
                    if args[0].startswith('Locale') and len(args) > 1:
                        args = args[1:]
                    inner = re.search(r'getString\(\s*R\.string\.(\w+)\s*\)', args[0])
                    if not inner:
                        continue
                    name, rest = inner.group(1), args[1:]
                else:
                    if not args:
                        continue
                    first = re.fullmatch(r'R\.string\.(\w+)', args[0])
                    if not first:
                        continue
                    name, rest = first.group(1), args[1:]
                if name not in default:
                    continue
                calls += 1
                line = text.count('\n', 0, m.start()) + 1
                where = '%s:%d' % (os.path.relpath(path, ROOT), line)
                for lang, strings in langs.items():
                    if name not in strings:
                        continue
                    ph = placeholders(strings[name][0])
                    count = max([p for p, _ in ph], default=0)
                    if rest and count == 0 and strings[name][1]:
                        warnings.append('%s: R.string.%s gets %d argument(s) but has no placeholders (%s)' % (
                            where, name, len(rest), lang or 'default'))
                    elif rest and len(rest) < count:
                        errors.append('%s: R.string.%s needs %d argument(s), gets %d (%s)' % (
                            where, name, count, len(rest), lang or 'default'))
                    elif not rest and count > 0 and m.group(1) == 'getString':
                        warnings.append('%s: R.string.%s has placeholders but is shown without arguments (%s)' % (
                            where, name, lang or 'default'))
                    for pos, conv in ph:
                        if conv in 'dxXo' and pos <= len(rest) and TEXTUAL.match(rest[pos - 1]):
                            errors.append('%s: R.string.%s %%%d$%s gets text "%s" (%s)' % (
                                where, name, pos, conv, rest[pos - 1], lang or 'default'))
    for e in errors:
        print('ERROR ' + e)
    for w in warnings:
        print('WARNING ' + w)
    print('string check: %d strings, %d calls checked, %d error(s), %d warning(s)' % (
        len(default), calls, len(errors), len(warnings)))
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
