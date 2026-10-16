#!/usr/bin/env python3
"""Bounded first-party GTK pointer fixture; run only inside the guest session."""
import json
import os
import sys

import gi
gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, Gdk

log_path = sys.argv[1]
rows = 2
cols = 2
state = {"allocation": None, "events": []}

def write_snapshot():
    with open(log_path + ".tmp", "w", encoding="utf-8") as out:
        json.dump(state, out, sort_keys=True)
    os.replace(log_path + ".tmp", log_path)

def allocate(widget, rectangle):
    state["allocation"] = {"x": rectangle.x, "y": rectangle.y,
                            "width": rectangle.width, "height": rectangle.height}
    write_snapshot()

def press(widget, event):
    allocation = widget.get_allocation()
    col = min(cols - 1, max(0, int(event.x * cols / max(1, allocation.width))))
    row = min(rows - 1, max(0, int(event.y * rows / max(1, allocation.height))))
    state["events"].append({"x": event.x, "y": event.y, "root_x": event.x_root,
                             "root_y": event.y_root, "row": row, "col": col,
                             "cell": row * cols + col})
    write_snapshot()
    return True

Gtk.init([])
window = Gtk.Window(title="Omamobile pointer geometry fixture")
window.fullscreen()
window.set_app_paintable(True)
drawing = Gtk.DrawingArea()
drawing.add_events(Gdk.EventMask.BUTTON_PRESS_MASK)
drawing.connect("size-allocate", allocate)
drawing.connect("button-press-event", press)
window.add(drawing)
window.show_all()
Gtk.main()
