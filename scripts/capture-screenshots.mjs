// Screenshots for the README, taken against a real running stack rather than mocked up.
//
// Runs inside the compose network, so every URL here is a service name: a browser in a container
// cannot reach the localhost the dashboard is normally built against. Bring the stack up with
// docker-compose.codespaces.yml and OPENPAY_PUBLIC_URL=http://edge:8000 first — scripts/shoot.sh
// does both and is the intended entry point.
//
// The failover shot is the one that matters, and it cannot be staged: the attempt rows only say
// what they say because an acquirer really was down when that payment went through.

import { chromium } from 'playwright';

const EDGE = process.env.EDGE_URL || 'http://edge:8000';
const DASHBOARD = process.env.DASHBOARD_URL || 'http://dashboard:8080';
const OUT = process.env.OUT_DIR || '/out';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });

async function shoot(name, locator) {
    const target = locator || page;
    await target.screenshot({ path: `${OUT}/${name}.png` });
    console.log(`  captured ${name}.png`);
}

// 1. The shop as a customer finds it.
console.log('Shop...');
await page.goto(EDGE, { waitUntil: 'networkidle' });
await page.waitForSelector('#catalog .qty button');
await sleep(400);
await shoot('shop', page.locator('.card'));

// 2. Put something in the basket. The Pay button is disabled by an empty basket as much as by a
//    missing key — one place decides both — so a card with nothing to buy never enables it.
//    The second button in a .qty pair is the one that adds; the first removes.
console.log('Adding to basket...');
await page.locator('#catalog .qty button').nth(1).click();
await sleep(300);

// 3. A payment. #fill-visa is the page's own test-instrument helper, used in preference to typing
//    a number field by field: it is what the page ships for exactly this, so it stays correct if
//    the form is ever reordered.
console.log('Paying...');
await page.locator('#fill-visa').click();
await sleep(300);
await shoot('shop-card-entered', page.locator('.card'));

await page.locator('#pay').click();

// Waited on the step being marked done, not on the word "Captured" appearing. The four steps are
// all rendered up front and greyed until reached, so matching body text photographs the payment
// mid-flight — caught it sitting on "Sent to the acquiring bank" with the last two still grey,
// which reads as a demo that stalled rather than one that finished.
await page
    .waitForFunction(() => document.querySelector('#s-captured')?.classList.contains('done'),
        null, { timeout: 90000 })
    .catch(() => console.log('  (never reached CAPTURED; capturing whatever is on screen)'));
await sleep(600);

// Clipped to the card. The page is a narrow panel on a wide background, and a full-viewport shot
// is mostly empty grey — which in a README renders as a small image surrounded by nothing.
await shoot('shop-captured', page.locator('#progress-view'));

// 3. The merchant's side. The login is minted at startup and printed by the shop itself, which is
//    also where this reads it from rather than having it pasted in.
console.log('Dashboard...');
const config = await page.evaluate(async (edge) => {
    const res = await fetch(`${edge}/api/checkout/config`);
    return res.json();
}, EDGE);

await page.goto(DASHBOARD, { waitUntil: 'networkidle' });
await page.locator('input[type="email"], input[name="email"], #email').first().fill(config.dashboardEmail);
await page.locator('input[type="password"], input[name="password"], #password').first().fill(config.dashboardPassword);
await page.getByRole('button', { name: /sign in|log ?in/i }).first().click();

await page.waitForFunction(() => !/sign in/i.test(document.title), null, { timeout: 30000 }).catch(() => {});
await sleep(2500);
await shoot('dashboard-overview');

// 4. The failover payment, which is the reason for all of this. Opening the most recent payment
//    and photographing the attempt list: one row per acquirer tried, the refusal kept beside the
//    acceptance.
await page.getByRole('link', { name: /payments/i }).first().click().catch(() => {});
await sleep(2000);
await shoot('dashboard-payments');

const row = page.locator('tbody tr').first();
if (await row.count()) {
    await row.click();
    await sleep(2500);
    await shoot('dashboard-payment-drawer');
}

await browser.close();
console.log('Done.');
