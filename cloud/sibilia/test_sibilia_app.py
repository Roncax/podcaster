"""Guards on sibilia_app.py's Modal configuration (read from source: Modal does not expose it locally)."""
import ast
from pathlib import Path

TREE = ast.parse(Path(__file__).with_name("sibilia_app.py").read_text())


def decorator_kwargs(name):
    for node in ast.walk(TREE):
        if isinstance(node, (ast.FunctionDef, ast.ClassDef)) and node.name == name:
            for dec in node.decorator_list:
                if isinstance(dec, ast.Call) and getattr(dec.func, "attr", None) in ("function", "cls"):
                    return {k.arg: ast.literal_eval(k.value) for k in dec.keywords if isinstance(k.value, ast.Constant)}
    raise AssertionError(f"no @app.function/@app.cls on {name}")


def test_web_front_end_waits_as_long_as_the_gpu_worker():
    # Queued GPU inputs (two shows at once, max_containers=2) must not time out on the web side first
    assert decorator_kwargs("api").get("timeout", 300) >= decorator_kwargs("Sibilia")["timeout"]
