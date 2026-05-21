const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const slides = [
  { name: 'slide-01-title', file: 'templates/slide-01-title.html' },
  { name: 'slide-02-migration', file: 'templates/slide-02-migration.html' },
  { name: 'slide-03-event-flow', file: 'templates/slide-03-event-flow.html' },
  { name: 'slide-04-mode1-arch', file: 'templates/slide-04-mode1-arch.html' },
  { name: 'slide-05-mode1-trigger', file: 'templates/slide-05-mode1-trigger.html' },
  { name: 'slide-06-mode2-arch', file: 'templates/slide-06-mode2-arch.html' },
  { name: 'slide-07-mode2-transform', file: 'templates/slide-07-mode2-transform.html' },
  { name: 'slide-08-comparison', file: 'templates/slide-08-comparison.html' },
  { name: 'slide-09-demo-setup', file: 'templates/slide-09-demo-setup.html' },
  { name: 'slide-10-demo-commands', file: 'templates/slide-10-demo-commands.html' },
  { name: 'slide-11-fluentbit', file: 'templates/slide-11-fluentbit.html' },
  { name: 'slide-12-log-replay', file: 'templates/slide-12-log-replay.html' },
  { name: 'slide-13-reliability', file: 'templates/slide-13-reliability.html' },
  { name: 'slide-14-decision', file: 'templates/slide-14-decision.html' },
  { name: 'slide-15-redhat', file: 'templates/slide-15-redhat.html' }
];

async function generateSlide(browser, slide) {
  console.log(`Generating ${slide.name}...`);

  const page = await browser.newPage();
  await page.setViewport({ width: 1920, height: 1080 });

  const htmlPath = path.join(__dirname, slide.file);
  await page.goto(`file://${htmlPath}`, { waitUntil: 'networkidle0' });

  const outputPath = path.join(__dirname, 'output', `${slide.name}.png`);
  await page.screenshot({
    path: outputPath,
    fullPage: false
  });

  await page.close();
  console.log(`✓ ${slide.name}.png`);
}

async function main() {
  console.log('Starting slide generation...\n');

  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });

  for (const slide of slides) {
    await generateSlide(browser, slide);
  }

  await browser.close();
  console.log('\n✓ All slides generated successfully!');
  console.log('Output: presentation/output/');
}

main().catch(console.error);
