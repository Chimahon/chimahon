"""Inspect the portable compiler's computed text styles for one oracle case (no Android)."""
import argparse
import ctypes as c
import json
import os
import sys
from pathlib import Path

parser = argparse.ArgumentParser()
sys.stdout.reconfigure(encoding="utf-8")
parser.add_argument("cases", type=Path)
parser.add_argument("case_id")
args = parser.parse_args()
os.add_dll_directory("C:/mingw-w64/mingw64/bin")
root = Path(__file__).resolve().parents[2]
lib = c.CDLL(str(root / "tools/native-dictionary/out/release/libdictionary_core.dll"))

class Source(c.Structure):
    _fields_ = [("text", c.c_char_p), ("size", c.c_size_t)]

class Error(c.Structure):
    _fields_ = [("source", c.c_uint32), ("offset", c.c_size_t), ("message", c.c_char * 192)]

class Options(c.Structure):
    _fields_ = [("size", c.c_float), ("dark", c.c_int), ("expanded", c.c_int), ("minimum_size", c.c_float)]

class Run(c.Structure):
    _fields_ = [(name, c.c_uint32) for name in ("offset", "size", "style", "role", "ruby", "paragraph")]

class Property(c.Structure):
    _fields_ = [(name, c.c_uint32) for name in ("name", "name_size", "value", "value_size")]

class Image(c.Structure):
    _fields_ = [(name, c.c_uint32) for name in ("run", "first", "count")]

class Style(c.Structure):
    _fields_ = [("first", c.c_uint32), ("count", c.c_uint32), ("size", c.c_float)]

class View(c.Structure):
    _fields_ = sum(([(name, c.c_void_p), (name + "_count", c.c_size_t)] for name in
        ("text", "runs", "paragraphs", "rubies", "tables", "rows", "cells", "styles", "properties", "flow", "images")), []) + [
        ("sources", c.c_size_t), ("elements", c.c_uint64), ("unresolved", c.c_uint64), ("unsupported", c.c_uint64),
        ("base_size", c.c_float), ("inline_boxes", c.c_void_p), ("inline_count", c.c_size_t)]

lib.dc_prepare_utf8.argtypes = [c.POINTER(Source), c.c_size_t, c.POINTER(c.c_void_p), c.POINTER(Error)]
lib.dc_stylesheets_create.argtypes = lib.dc_prepare_utf8.argtypes
lib.dc_compile_styled_configured.argtypes = [c.c_void_p, c.c_void_p, c.POINTER(c.c_uint32), c.c_size_t,
    c.POINTER(Options), c.POINTER(c.c_void_p), c.POINTER(Error)]
lib.dc_prepared_document_view.argtypes = [c.c_void_p]
lib.dc_prepared_document_view.restype = View
for name in ("dc_document_destroy", "dc_stylesheets_destroy", "dc_prepared_document_destroy"):
    getattr(lib, name).argtypes = [c.c_void_p]

case = next(case for case in json.loads(args.cases.read_text(encoding="utf-8")) if case["id"] == args.case_id)
raw = case["glossary"].encode()
css = ((root / "chimahon/src/main/assets/dictionary/base.css").read_text(encoding="utf-8") +
    (Path(case["dictCss"]).read_text(encoding="utf-8") if case.get("dictCss") else "")).encode()
document, sheets, prepared, error = c.c_void_p(), c.c_void_p(), c.c_void_p(), Error()
def check(status):
    if status: raise RuntimeError(bytes(error.message).decode())
try:
    check(lib.dc_prepare_utf8(c.byref(Source(raw, len(raw))), 1, c.byref(document), c.byref(error)))
    check(lib.dc_stylesheets_create(c.byref(Source(css, len(css))), 1, c.byref(sheets), c.byref(error)))
    check(lib.dc_compile_styled_configured(document, sheets, c.byref(c.c_uint32(0)), 1,
        c.byref(Options(case["fontSize"], case.get("isDark", False), 1, 8)), c.byref(prepared), c.byref(error)))
    view = lib.dc_prepared_document_view(prepared)
    runs = c.cast(view.runs, c.POINTER(Run))
    styles = c.cast(view.styles, c.POINTER(Style))
    properties = c.cast(view.properties, c.POINTER(Property))
    def text(offset, count): return c.string_at(view.text + offset, count).decode()
    def style_properties(index):
        return {text(p.name,p.name_size):text(p.value,p.value_size) for p in
            (properties[j] for j in range(styles[index].first,styles[index].first+styles[index].count))
            if not text(p.name,p.name_size).startswith("--")}
    image_styles = {}
    for image in c.cast(view.images,c.POINTER(Image))[:view.images_count]:
        props={text(p.name,p.name_size):text(p.value,p.value_size) for p in
            (properties[j] for j in range(image.first,image.first+image.count))}
        if "container-style" in props:
            image_styles[image.run]=style_properties(int(props["container-style"]))
    for i in range(view.runs_count):
        run = runs[i]
        props = {text(p.name, p.name_size): text(p.value, p.value_size) for p in
            (properties[j] for j in range(styles[run.style].first, styles[run.style].first + styles[run.style].count))}
        props = {key: value for key, value in props.items() if not key.startswith("--")}
        result=dict(paragraph=run.paragraph, role=run.role, text=text(run.offset, run.size), style=props)
        if i in image_styles: result["image_container"]=image_styles[i]
        print(json.dumps(result, ensure_ascii=False))
finally:
    lib.dc_prepared_document_destroy(prepared)
    lib.dc_stylesheets_destroy(sheets)
    lib.dc_document_destroy(document)
