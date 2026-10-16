import importlib.util
import json
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "verify-display-geometry.py"
SPEC = importlib.util.spec_from_file_location("verify_display_geometry", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def test_display_ids_and_geometry_are_parsed_from_dumpsys():
    text = "mBaseDisplayInfo=DisplayInfo{displayId=0, real 1080 x 2400}\n" \
        "mBaseDisplayInfo=DisplayInfo{displayId=12, real 1280 x 720}"
    assert MODULE.display_ids(text) == {0, 12}
    assert MODULE.has_geometry(text, 1280, 720)
    assert not MODULE.has_geometry(text, 720, 1280)


def test_probe_report_requires_expected_and_observed_geometry():
    report = {"expectedWidth": 720, "expectedHeight": 1280, "actualWidth": 720,
              "actualHeight": 1280, "desktopDisplayId": "14"}
    assert MODULE.report_value(json.dumps(report))["desktopDisplayId"] == "14"


def test_probe_report_rejects_missing_observation():
    try:
        MODULE.report_value(json.dumps({"expectedWidth": 720}))
    except ValueError as error:
        assert "expectedHeight" in str(error)
    else:
        raise AssertionError("missing geometry report field was accepted")
