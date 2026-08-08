// Records the README's animation: a card typed into a shop, and a payment that reaches captured
// without anything else being clicked.
//
// Recorded with an acquirer stopped, deliberately. It makes the clip watchable — the steps tick
// over about six seconds instead of blurring past in one — and it is the more honest thing to
// show, because the payment really did have to fail over to a second bank to complete. What the
// viewer sees is a shop that does not notice.
//
// Sized narrow on purpose: the checkout is a single column, and a wide viewport spends most of a
// GIF's byte budget on empty background.

import { chromium } from 'playwright';

const EDGE = process.env.EDGE_URL || 'http://edge:8000';
const OUT = process.env.OUT_DIR || '/out';
const SIZE = { width: 760, height: 900 };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();
const context = await browser.newContext({
    viewport: SIZE,
    recordVideo: { dir: `${OUT}/video`, size: SIZE },
});
const page = await context.newPage();

await page.goto(EDGE, { waitUntil: 'networkidle' });
await page.waitForSelector('#catalog .qty button');
await sleep(1200);

// Two of the first item, clicked with a beat between them so the total visibly changes rather
// than jumping. The recording has no cursor, so state changes are the only thing that reads.
const add = page.locator('#catalog .qty button').nth(1);
await add.click();
await sleep(700);
await add.click();
await sleep(1100);

// Typed rather than filled, at a speed a person could read. #fill-visa would be one frame.
await page.locator('#card').click();
await page.locator('#card').pressSequentially('4242 4242 4242 4242', { delay: 55 });
await sleep(400);
await page.locator('#exp').pressSequentially('12 / 30', { delay: 70 });
await sleep(300);
await page.locator('#cvc').pressSequentially('123', { delay: 90 });
await sleep(900);

await page.locator('#pay').click();

// The point of the clip: four steps going green on their own.
await page
    .waitForFunction(() => document.querySelector('#s-captured')?.classList.contains('done'),
        null, { timeout: 90000 })
    .catch(() => console.log('  (never reached CAPTURED)'));

// Held at the end so the last frame is readable, and so a looping GIF does not snap away from the
// result the instant it arrives.
await sleep(2600);

// The video is only written on close, and its name is assigned by Playwright — so ask the page
// for it rather than guessing.
const video = page.video();
await context.close();
await browser.close();

const path = await video.path();
console.log(`video: ${path}`);
