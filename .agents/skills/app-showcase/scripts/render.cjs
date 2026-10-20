// Export a local composition. No live app interaction or network access.
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const args = process.argv.slice(2);
const input = args.shift();
function option(name) {
  const i = args.indexOf(name);
  if (i < 0) return undefined;
  if (!args[i + 1]) throw Error('Missing value: ' + name);
  return args[i + 1];
}
const output = option('--output');
if (!input || !output) throw Error('Usage: render.cjs HTML --output PNG [--module-root NODE_MODULES] [--browser EXECUTABLE]');
const root = option('--module-root');
const {chromium} = require(root ? path.resolve(root, 'playwright') : 'playwright');
(async () => {
  const browser = await chromium.launch({headless: true, executablePath: option('--browser')});
  try {
    const page = await browser.newPage({viewport: {width: 2000, height: 1250}, deviceScaleFactor: 1});
    await page.route('**/*', route => /^(file:|data:)/.test(route.request().url()) ? route.continue() : route.abort());
    await page.goto(pathToFileURL(path.resolve(input)).href, {waitUntil: 'load'});
    const dimensions = await page.locator('meta[name="showcase-size"]').getAttribute('content');
    if (!/^\d{3,4}x\d{3,4}$/.test(dimensions)) throw Error('Invalid canvas dimensions');
    const [width, height] = dimensions.split('x').map(Number);
    if (width > 6000 || height > 6000) throw Error('Canvas too large');
    await page.setViewportSize({width, height});
    const checked = await page.evaluate(async () => {
      await document.fonts.ready;
      for (const image of document.images) {
        await image.decode();
        if (image.naturalWidth !== +image.getAttribute('width') || image.naturalHeight !== +image.getAttribute('height'))
          throw Error('Capture dimensions changed');
        const box = image.getBoundingClientRect();
        if (Math.abs(box.width / box.height - image.naturalWidth / image.naturalHeight) > .002)
          throw Error('Capture aspect ratio changed');
        if (box.bottom > document.querySelector('.details').getBoundingClientRect().top)
          throw Error('Capture overlaps text');
      }
      for (const node of document.querySelectorAll('[data-fit]'))
        if (node.scrollHeight > node.clientHeight + 1 || node.scrollWidth > node.clientWidth + 1)
          throw Error('Text overflow: ' + node.textContent.slice(0, 80));
      if (document.documentElement.scrollHeight > innerHeight || document.documentElement.scrollWidth > innerWidth)
        throw Error('Canvas overflow');
      return {images: document.images.length, width: innerWidth, height: innerHeight};
    });
    fs.mkdirSync(path.dirname(path.resolve(output)), {recursive: true});
    await page.screenshot({path: output, fullPage: false});
    console.log(JSON.stringify({...checked, output: path.resolve(output)}));
  } finally { await browser.close(); }
})().catch(error => {console.error(error.message); process.exitCode = 1;});
