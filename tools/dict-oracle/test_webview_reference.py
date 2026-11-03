import base64
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from PIL import Image

HERE = Path(__file__).resolve().parent
CHROME = Path("C:/Program Files/Google/Chrome/Application/chrome.exe")


@unittest.skipUnless(CHROME.is_file(), "Chrome is required for computed-style and pixel checks")
class WebViewReferenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from playwright.sync_api import sync_playwright
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(executable_path=str(CHROME), headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(dir=HERE / "out")
        self.addCleanup(self.folder.cleanup)
        self.page = self.browser.new_page()
        self.addCleanup(self.page.close)

    def load(self, dark):
        root = Path(self.folder.name)
        payload = root / "payload.json"
        payload.write_text(json.dumps({"isDark": dark, "results": []}), encoding="utf-8")
        page = root / "page.html"
        subprocess.run(["node", str(HERE / "build_page.mjs"), f"--payload={payload}", f"--out={page}"],
                       check=True, capture_output=True)
        self.page.goto(page.as_uri())
        self.assertTrue(self.page.evaluate("window.__rendered"))

    def test_dark_palette_and_aliases_are_readable(self):
        self.load(True)
        colors = self.page.evaluate("""() => {
            const root = getComputedStyle(document.documentElement);
            return [getComputedStyle(document.body).color, root.getPropertyValue('--bg').trim(),
                root.getPropertyValue('--text-color').trim(), root.getPropertyValue('--border').trim()];
        }""")
        self.assertEqual(colors, ["rgb(227, 227, 227)", "#000000", "#e3e3e3", "rgba(255, 255, 255, 0.10)"])

    def test_light_palette_is_unchanged(self):
        self.load(False)
        self.assertEqual(self.page.evaluate("getComputedStyle(document.body).color"), "rgb(31, 31, 31)")
        self.assertEqual(self.page.evaluate("getComputedStyle(document.body).backgroundColor"), "rgb(255, 255, 255)")

    def test_background_false_does_not_hide_monochrome_foreground(self):
        self.load(False)
        png = io.BytesIO()
        Image.new("RGBA", (1, 1), (0, 0, 0, 255)).save(png, format="PNG")
        uri = "data:image/png;base64," + base64.b64encode(png.getvalue()).decode("ascii")
        for appearance in ("auto", "monochrome"):
            for background in ("true", "false"):
                with self.subTest(appearance=appearance, background=background):
                    self.page.evaluate("""({appearance, background, uri}) => {
                        document.body.innerHTML = `<span class="gloss-image-link" data-appearance="${appearance}"
                            data-background="${background}" style="color:rgb(200,40,80)">
                            <span class="gloss-image-container" style="width:20px;height:20px">
                                <span class="gloss-image-background"></span><img class="gloss-image">
                            </span></span>`;
                        document.querySelector('.gloss-image-background').style.setProperty('--image', `url("${uri}")`);
                        document.querySelector('img').src = uri;
                        return document.querySelector('img').decode();
                    }""", dict(appearance=appearance, background=background, uri=uri))
                    layer = self.page.locator(".gloss-image-background")
                    visible = layer.evaluate("e => getComputedStyle(e).display !== 'none'")
                    self.assertEqual(visible, appearance == "monochrome" or background == "true")
                    shot = Image.open(io.BytesIO(self.page.locator(".gloss-image-container").screenshot())).convert("RGB")
                    self.assertEqual(shot.getpixel((10, 10)), (200, 40, 80) if appearance == "monochrome" else (0, 0, 0))


if __name__ == "__main__":
    unittest.main()
