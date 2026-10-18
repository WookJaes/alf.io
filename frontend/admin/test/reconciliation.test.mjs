import {after, before, test} from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createServer} from 'vite';
import {chromium} from 'playwright';

const root = fileURLToPath(new URL('../', import.meta.url));
const header = id => ({comparisonId: id, comparedAt: '2026-10-18T00:00:00Z', expiresAt: '2026-10-19T00:00:00Z',
    aiAvailable: true, summary: {csvRows: 3, csvTickets: 3, systemTickets: 3, matchedTickets: 1,
        attentionTickets: 2, findings: {CSV_ONLY: 0, SYSTEM_ONLY: 0, DUPLICATE: 0, EMAIL_MISMATCH: 2}}});
const item = (reference, matched = false) => ({reference, uuid: reference, csvRows: [{rowNumber: 2, email: 'csv@example.invalid'}],
    system: {email: 'system@example.invalid', status: 'ACQUIRED', reservationId: 'synthetic'},
    findings: matched ? [] : ['EMAIL_MISMATCH'], emailMissing: false});
let server, browser, origin, screenshotIndex = 0;
before(async () => {
    server = await createServer({root, configFile: false, server: {host: '127.0.0.1', port: 0}});
    await server.listen();
    origin = `http://127.0.0.1:${server.httpServer.address().port}`;
    browser = await chromium.launch({headless: true, ...(process.env.CHROME_PATH ? {executablePath: process.env.CHROME_PATH} : {})});
    console.log(`환경: Node ${process.version}, Chromium ${browser.version()}, Vite 합성 API`);
});
after(async () => { await browser?.close(); await server?.close(); });

async function fixture(t, {fail = false, delay = false} = {}) {
    const context = await browser.newContext({permissions: ['clipboard-read', 'clipboard-write']});
    const page = await context.newPage();
    const screenshot = ++screenshotIndex;
    t.after(async () => {
        try {
            if (process.env.SCREENSHOT_DIR) {
                await mkdir(process.env.SCREENSHOT_DIR, {recursive: true});
                await page.screenshot({path: resolve(process.env.SCREENSHOT_DIR, `${screenshot}.png`), fullPage: true});
            }
        } finally { await context.close(); }
    });
    let failRequests = false, hold = false, release;
    await page.route('**/admin/api/**', async route => {
        const url = new URL(route.request().url());
        if (hold) await new Promise(resolve => { release = resolve; });
        if (failRequests) return route.fulfill({status: 503, json: {}});
        const id = url.pathname.includes('/comparison-b') ? 'comparison-b' : 'comparison-a';
        if (url.pathname.endsWith('/drafts')) return route.fulfill({json: {entries: [{reference: 'attention-0', explanation: '합성 근거', nextStep: '직접 확인', draft: '합성 초안'}], model: 'synthetic', inputTokens: 1, cachedInputTokens: 0, outputTokens: 1, durationMillis: 1}});
        if (route.request().method() === 'DELETE') return route.fulfill({status: 204});
        if (route.request().method() === 'POST') return route.fulfill({json: header('comparison-b')});
        if (url.pathname.endsWith('/attendee-reconciliations')) return route.fulfill({json: [header('comparison-a'), header('comparison-b')]});
        const index = Number(url.searchParams.get('page') || 0), matched = url.searchParams.get('filter') === 'MATCHED';
        return route.fulfill({json: {header: header(id), page: index, pageSize: 1, total: matched ? 1 : 2,
            items: [item(matched ? 'matched-0' : `attention-${index}`, matched)]}});
    });
    await page.goto(`${origin}/test/reconciliation.html`);
    const ui = page.locator('alfio-attendee-reconciliation');
    await ui.locator('#history').selectOption('comparison-a');
    await ui.locator('[data-reference="attention-0"]').waitFor();
    return {page, ui, fail: () => {failRequests = fail; hold = delay;}, release: () => {hold = false; release?.();},
        async draft() {
            await ui.locator('[data-reference] input').check();
            await ui.locator('#generate').click();
            await ui.locator('textarea').fill('사용자가 편집한 합성 초안');
            failRequests = fail; hold = delay;
        }};
}
async function settled(ui) { await ui.locator('section.panel[aria-busy="false"]').waitFor(); }
async function preserved(ui) {
    assert.equal(await ui.locator('textarea').count(), 1, '편집 초안이 보존되어야 한다');
    assert.equal(await ui.locator('textarea').inputValue(), '사용자가 편집한 합성 초안');
    assert.match(await ui.locator('#generate').innerText(), /선택 1개/);
}

test('정상 대조군: 필터와 목록을 함께 전환한다', async t => {
    const {ui} = await fixture(t);
    await ui.locator('#filter').selectOption('MATCHED'); await settled(ui);
    assert.equal(await ui.locator('#filter').inputValue(), 'MATCHED');
    assert.equal(await ui.locator('[data-reference]').getAttribute('data-reference'), 'matched-0');
});
test('503 필터 실패: 마지막 성공 필터와 목록을 보존한다', async t => {
    const f = await fixture(t, {fail: true}); f.fail();
    await f.ui.locator('#filter').selectOption('MATCHED'); await settled(f.ui);
    assert.match(await f.ui.locator('[role=alert]').innerText(), /요청을 처리하지 못했습니다/);
    assert.equal(await f.ui.locator('#filter').inputValue(), 'ATTENTION');
    assert.equal(await f.ui.locator('[data-reference]').getAttribute('data-reference'), 'attention-0');
});
for (const action of ['page', 'filter']) for (const fail of [false, true]) {
    test(`${fail ? '503 실패' : '정상 전환'} ${action}: 선택 항목과 편집 초안을 보존한다`, async t => {
        const f = await fixture(t, {fail}); await f.draft();
        if (action === 'page') await f.ui.locator('#next').click();
        else await f.ui.locator('#filter').selectOption('ALL');
        await settled(f.ui); await preserved(f.ui);
        assert.equal(await f.ui.locator('#filter').inputValue(), action === 'filter' && !fail ? 'ALL' : 'ATTENTION');
        assert.equal(await f.ui.locator('[data-reference]').getAttribute('data-reference'), action === 'page' && !fail ? 'attention-1' : 'attention-0');
        if (fail) assert.equal(await f.ui.locator('[data-reference] input').isChecked(), true);
    });
}

test('요청 중에는 이전 필터·목록·초안을 표시하고 성공 후 함께 반영한다', async t => {
    const f = await fixture(t, {delay: true}); await f.draft();
    const request = f.page.waitForRequest(request => request.url().includes('filter=MATCHED'));
    await f.ui.locator('#filter').selectOption('MATCHED');
    await request;
    assert.equal(await f.ui.locator('#filter').inputValue(), 'ATTENTION');
    assert.equal(await f.ui.locator('[data-reference]').getAttribute('data-reference'), 'attention-0');
    await preserved(f.ui);
    f.release(); await settled(f.ui);
    assert.equal(await f.ui.locator('#filter').inputValue(), 'MATCHED');
    assert.equal(await f.ui.locator('[data-reference]').getAttribute('data-reference'), 'matched-0');
    await preserved(f.ui);
});
test('왕복 페이지 이동 후 선택 표시·편집·복사를 유지한다', async t => {
    const f = await fixture(t); await f.draft();
    await f.ui.locator('#next').click(); await settled(f.ui);
    await f.ui.locator('#previous').click(); await settled(f.ui);
    await preserved(f.ui);
    assert.equal(await f.ui.locator('[data-reference] input').isChecked(), true);
    await f.ui.locator('textarea').fill('왕복 뒤 수정한 초안');
    await f.ui.getByRole('button', {name: '초안 복사', exact: true}).click();
    await f.ui.locator('.success').waitFor();
    assert.equal(await f.page.evaluate(() => navigator.clipboard.readText()), '왕복 뒤 수정한 초안');
});
for (const fail of [false, true]) {
    test(`다른 비교 조회 ${fail ? '실패 보존' : '성공 시 초안 연결 해제'}`, async t => {
        const f = await fixture(t, {fail}); await f.draft();
        await f.ui.locator('#history').selectOption('comparison-b'); await settled(f.ui);
        assert.equal(await f.ui.locator('#history').inputValue(), fail ? 'comparison-a' : 'comparison-b');
        if (fail) await preserved(f.ui);
        else {
            assert.equal(await f.ui.locator('textarea').count(), 0);
            assert.match(await f.ui.locator('#generate').innerText(), /선택 0개/);
        }
    });
    test(`새 비교 ${fail ? '실패 시 필터·초안 보존' : '성공 시 확인 필요로 전환·초안 연결 해제'}`, async t => {
        const f = await fixture(t, {fail});
        await f.ui.locator('#filter').selectOption('ALL'); await settled(f.ui);
        await f.draft();
        await f.ui.locator('#csv-file').setInputFiles({name: 'synthetic.csv', mimeType: 'text/csv', buffer: Buffer.from('ID,E-Mail\nsynthetic,csv@example.invalid')});
        await f.ui.locator('#compare').click(); await settled(f.ui);
        assert.equal(await f.ui.locator('#filter').inputValue(), fail ? 'ALL' : 'ATTENTION');
        assert.equal(await f.ui.locator('#history').inputValue(), fail ? 'comparison-a' : 'comparison-b');
        if (fail) await preserved(f.ui);
        else assert.equal(await f.ui.locator('textarea').count(), 0);
    });
    test(`비교 삭제 ${fail ? '실패 시 결과·초안 보존' : '성공 시 결과·초안 연결 해제'}`, async t => {
        const f = await fixture(t, {fail}); await f.draft();
        await f.ui.locator('#delete-comparison').click(); await settled(f.ui);
        if (fail) {
            await preserved(f.ui);
            assert.equal(await f.ui.locator('[data-reference]').getAttribute('data-reference'), 'attention-0');
        } else {
            assert.equal(await f.ui.locator('[data-reference]').count(), 0);
            assert.equal(await f.ui.locator('textarea').count(), 0);
        }
    });
}
