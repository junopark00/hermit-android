"""Checks that views looked up in Java have the type the layouts give them.

Hermit overrides upstream layouts in the nonRoot flavour (app/src/nonRoot/res/layout*). If an
override changes a view's class (a RelativeLayout becomes a LinearLayout, say) while the Java code
still casts it to the old class, everything compiles and the app crashes as soon as the screen
opens (ClassCastException). This script catches that before a build.

For every `Type name = findViewById(R.id.x)`, `(Type) findViewById(R.id.x)` and field assignment
`name = findViewById(R.id.x)` (field declared as `Type name`), it finds the layouts the same Java
file inflates (setContentView / inflate with R.layout.y), in every flavour and qualifier
(layout, layout-land, ...), and checks that each view with that id is the declared type or a
subclass of it.

Usage: python hermit/check-view-types.py   (exit code 1 on a mismatch)
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
SRC = os.path.join(ROOT, 'app', 'src')
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'

# Framework widget classes and their parents (enough for the views this app uses)
FRAMEWORK_PARENTS = {
    'ViewGroup': 'View',
    'LinearLayout': 'ViewGroup', 'RelativeLayout': 'ViewGroup', 'FrameLayout': 'ViewGroup',
    'GridLayout': 'ViewGroup', 'TableLayout': 'LinearLayout', 'TableRow': 'LinearLayout',
    'RadioGroup': 'LinearLayout', 'ScrollView': 'FrameLayout', 'HorizontalScrollView': 'FrameLayout',
    'AdapterView': 'ViewGroup', 'AbsListView': 'AdapterView', 'ListView': 'AbsListView',
    'GridView': 'AbsListView', 'AbsSpinner': 'AdapterView', 'Spinner': 'AbsSpinner',
    'TextView': 'View', 'EditText': 'TextView', 'Button': 'TextView', 'CompoundButton': 'Button',
    'CheckBox': 'CompoundButton', 'RadioButton': 'CompoundButton', 'Switch': 'CompoundButton',
    'ToggleButton': 'CompoundButton', 'CheckedTextView': 'TextView',
    'ImageView': 'View', 'ImageButton': 'ImageView',
    'ProgressBar': 'View', 'AbsSeekBar': 'ProgressBar', 'SeekBar': 'AbsSeekBar', 'RatingBar': 'AbsSeekBar',
    'SurfaceView': 'View', 'TextureView': 'View', 'Space': 'View', 'ViewStub': 'View',
    'WebView': 'FrameLayout',
}

LOOKUP = re.compile(r'(?:\(\s*([A-Z]\w*)\s*\)\s*)?(?:\w+\s*\.\s*)?findViewById\(\s*R\.id\.(\w+)\s*\)')
DECLARED = re.compile(r'\b([A-Z]\w*)(?:<[^>]*>)?\s+(\w+)\s*=\s*$')
ASSIGNED = re.compile(r'\b(\w+)\s*=\s*$')
LAYOUT_REF = re.compile(r'R\.layout\.(\w+)')


def java_files():
    for base, _, files in os.walk(SRC):
        for f in files:
            if f.endswith('.java'):
                yield os.path.join(base, f)


def custom_parents():
    """Simple class name -> parent simple name, for the app's own view classes."""
    parents = {}
    for path in java_files():
        text = open(path, encoding='utf-8', errors='replace').read()
        for m in re.finditer(r'\bclass\s+(\w+)\s+extends\s+([\w.]+)', text):
            parents[m.group(1)] = m.group(2).split('.')[-1]
    return parents


def layouts():
    """Layout name -> list of (file, {id: view class simple name})."""
    result = {}
    for base, _, files in os.walk(SRC):
        if os.path.basename(base).split('-')[0] != 'layout' or os.sep + 'res' + os.sep not in base + os.sep:
            continue
        for f in files:
            if not f.endswith('.xml'):
                continue
            path = os.path.join(base, f)
            ids = {}
            for el in ET.parse(path).iter():
                vid = el.get(ANDROID_NS + 'id')
                if not vid or not vid.startswith('@+id/') and not vid.startswith('@id/'):
                    continue
                tag = el.tag
                if tag == 'view':
                    tag = el.get('class', 'View')
                ids[vid.split('/', 1)[1]] = tag.split('.')[-1]
            result.setdefault(f[:-4], []).append((path, ids))
    return result


def ancestors(cls, parents):
    seen = []
    while cls and cls not in seen:
        seen.append(cls)
        cls = FRAMEWORK_PARENTS.get(cls) or parents.get(cls)
    return seen


def main():
    parents = custom_parents()
    all_layouts = layouts()
    problems = []
    checked = 0
    for path in java_files():
        text = open(path, encoding='utf-8', errors='replace').read()
        used_layouts = set(LAYOUT_REF.findall(text)) & set(all_layouts)
        fields = {m.group(2): m.group(1) for m in re.finditer(r'\b(?:private|protected|public)?\s*(?:final\s+)?([A-Z]\w*)\s+(\w+)\s*;', text)}
        for m in LOOKUP.finditer(text):
            cast, vid = m.group(1), m.group(2)
            line_start = text.rfind('\n', 0, m.start()) + 1
            before = text[line_start:m.start()]
            declared = cast
            if not declared:
                d = DECLARED.search(before)
                if d and d.group(1) not in ('return',):
                    declared = d.group(1)
                else:
                    a = ASSIGNED.search(before)
                    if a and a.group(1) in fields:
                        declared = fields[a.group(1)]
            if not declared or declared in ('View', 'T', 'V'):
                continue
            candidates = [(lp, ids[vid]) for name in used_layouts for lp, ids in all_layouts[name] if vid in ids]
            if not candidates:
                continue
            checked += 1
            for layout_path, tag in candidates:
                chain = ancestors(tag, parents)
                # Only when the whole chain up to View is known (an unknown library class is skipped)
                if declared not in chain and chain[-1] == 'View':
                    line = text.count('\n', 0, m.start()) + 1
                    problems.append('%s:%d: R.id.%s is used as %s, but %s makes it a %s' % (
                        os.path.relpath(path, ROOT), line, vid, declared, os.path.relpath(layout_path, ROOT), tag))
    for p in problems:
        print('ERROR ' + p)
    print('view type check: %d lookups checked, %d problem(s)' % (checked, len(problems)))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
