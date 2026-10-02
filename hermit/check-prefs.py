"""Checks that every SharedPreferences key is read and written with one type.

A key saved as a String and read with getInt() (or a CheckBoxPreference read with getString())
throws ClassCastException at runtime only, typically right when a screen opens. The script
collects get*/put* calls (keys as literals or String constants) and the types the preference
screens (res/xml) store, and reports a key used with two types.

Usage: python hermit/check-prefs.py   (exit code 1 on a conflict)
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
SRC = os.path.join(ROOT, 'app', 'src')
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'

# Preference classes and the type they store
XML_TYPES = {
    'CheckBoxPreference': 'Boolean', 'SwitchPreference': 'Boolean', 'TwoStatePreference': 'Boolean',
    'ListPreference': 'String', 'EditTextPreference': 'String', 'MultiSelectListPreference': 'StringSet',
}
ACCESS = re.compile(r'\.(get|put)(String|Int|Boolean|Float|Long|StringSet)\s*\(\s*([^,()]+?)\s*[,)]')
CONST = re.compile(r'\bstatic\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"')


def java_files():
    for base, _, files in os.walk(SRC):
        for f in files:
            if f.endswith('.java'):
                yield os.path.join(base, f)


def custom_pref_types():
    """Custom Preference classes: class name -> stored type, from their persist*/getPersisted* calls."""
    types = {}
    for path in java_files():
        text = open(path, encoding='utf-8', errors='replace').read()
        m = re.search(r'\bclass\s+(\w+)\s+extends\s+\w*Preference\b', text)
        if not m:
            continue
        stored = set(re.findall(r'\b(?:persist|getPersisted)(String|Int|Boolean|Float|Long)\s*\(', text))
        if len(stored) == 1:
            types[m.group(1)] = stored.pop()
    return types


def main():
    constants = {}
    texts = {}
    for path in java_files():
        text = open(path, encoding='utf-8', errors='replace').read()
        texts[path] = text
        for name, value in CONST.findall(text):
            constants.setdefault(name, set()).add(value)

    uses = {}  # key -> {type: [where]}

    def add(key, typ, where):
        uses.setdefault(key, {}).setdefault(typ, []).append(where)

    for path, text in texts.items():
        for m in ACCESS.finditer(text):
            expr = m.group(3)
            typ = m.group(2)
            if expr.startswith('"') and expr.endswith('"'):
                keys = {expr[1:-1]}
            else:
                name = expr.split('.')[-1]
                keys = constants.get(name)
                if not keys or len(keys) != 1:
                    continue  # a variable or an ambiguous constant
            # Only SharedPreferences-like calls: Bundle/Intent getters share names; skip those
            line_start = text.rfind('\n', 0, m.start()) + 1
            receiver = text[line_start:m.start()]
            if re.search(r'(?:intent|extras|bundle|savedInstanceState|args|getIntent\(\)|data)\s*$', receiver, re.I):
                continue
            line = text.count('\n', 0, m.start()) + 1
            for key in keys:
                add(key, typ, '%s:%d' % (os.path.relpath(path, ROOT), line))

    custom = custom_pref_types()
    for flavour in ('main', 'nonRoot'):
        xml_dir = os.path.join(SRC, flavour, 'res', 'xml')
        if not os.path.isdir(xml_dir):
            continue
        for f in os.listdir(xml_dir):
            path = os.path.join(xml_dir, f)
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            for el in root.iter():
                key = el.get(ANDROID_NS + 'key')
                if not key:
                    continue
                cls = el.tag.split('.')[-1]
                typ = XML_TYPES.get(cls) or custom.get(cls)
                if typ:
                    add(key, typ, '%s (%s)' % (os.path.relpath(path, ROOT), cls))

    conflicts = 0
    for key, types in sorted(uses.items()):
        if len(types) > 1:
            conflicts += 1
            print('ERROR key "%s" is used as %s' % (key, ', '.join(sorted(types))))
            for typ, wheres in sorted(types.items()):
                for w in wheres[:4]:
                    print('    %s: %s' % (typ, w))
    print('prefs check: %d keys, %d conflict(s)' % (len(uses), conflicts))
    return 1 if conflicts else 0


if __name__ == '__main__':
    sys.exit(main())
