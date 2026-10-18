import {after, before, test} from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {createServer} from 'vite';
import {chromium} from 'playwright';

let server, browser, origin;
before(async () => {
    server = await createServer({root: fileURLToPath(new URL('../', import.meta.url)), configFile: false, server: {host: '127.0.0.1', port: 0}});
    await server.listen();
    origin = `http://127.0.0.1:${server.httpServer.address().port}`;
    browser = await chromium.launch({headless: true, ...(process.env.CHROME_PATH ? {executablePath: process.env.CHROME_PATH} : {})});
});
after(async () => { await browser?.close(); await server?.close(); });

async function fixture(t, field) {
    const page = await browser.newPage();
    t.after(() => page.close());
    const requests = [];
    await page.route('**/admin/api/**', async route => {
        requests.push(route.request().postDataJSON());
        await route.fulfill({json: {success: true}});
    });
    await page.goto(`${origin}/test/required-dropdown.html`);
    await page.waitForFunction(() => window.openField != null);
    await page.evaluate(field => window.openField(field), field);
    const edit = page.locator('alfio-additional-field-edit');
    await edit.locator('sl-dialog[open]').waitFor();
    return {page, edit, requests};
}

const emptyField = {id: 42, type: 'select', name: 'synthetic', required: false, editable: true,
    restrictedValues: [], description: {en: {locale: 'en', description: {label: 'Synthetic dropdown', restrictedValues: {blue: 'Blue'}}}}};
const message = 'Add at least one selectable option for a required dropdown.';

test('필수 드롭다운 생성: 선택지 전체 삭제 후 저장 차단, 선택지 복구 후 저장', async t => {
    const {edit, requests} = await fixture(t);
    const title = edit.locator('sl-input[label="Title"] input');
    await title.fill('Synthetic dropdown'); await title.press('Tab');
    await edit.locator('sl-select[label="Field Type"]').click();
    await edit.locator('sl-option[value=select]').click();
    await edit.locator('sl-switch').filter({hasText: 'Required field'}).locator('[part=control]').click();
    await edit.locator('sl-button[variant=danger]').filter({hasText: 'delete'}).click();
    await edit.locator('sl-button[type=submit]').click();
    await edit.getByText(message, {exact: true}).waitFor({timeout: 3000});
    assert.equal(requests.length, 0);
    assert.equal(await edit.locator('sl-dialog[open]').count(), 1);
    await edit.getByText('Add option', {exact: true}).click();
    const description = edit.locator('sl-card input[placeholder^="enter description"]');
    await description.fill('Blue'); await description.press('Tab');
    await edit.locator('sl-button[type=submit]').click();
    await edit.locator('sl-dialog[open]').waitFor({state: 'hidden'});
    assert.equal(requests.length, 1);
    assert.deepEqual(requests[0].restrictedValues, [{value: 'blue', enabled: true}]);
    assert.equal(requests[0].required, true);
});

test('기존 빈 선택 드롭다운을 필수로 바꾸는 수정도 저장 차단', async t => {
    const {edit, requests} = await fixture(t, emptyField);
    await edit.locator('sl-switch').filter({hasText: 'Required field'}).locator('[part=control]').click();
    await edit.locator('sl-button[type=submit]').click();
    await edit.getByText(message, {exact: true}).waitFor({timeout: 3000});
    assert.equal(requests.length, 0);
    // Returning to optional is valid, and does not retain the old validation error.
    await edit.locator('sl-switch').filter({hasText: 'Required field'}).locator('[part=control]').click();
    await edit.locator('sl-button[type=submit]').click();
    await edit.locator('sl-dialog[open]').waitFor({state: 'hidden'});
    assert.equal(requests.length, 1);
    assert.equal(requests[0].required, false);
});

for (const type of ['select', 'input:text', 'country', 'checkbox', 'radio']) {
    test(`정상 수정 대조군 ${type}`, async t => {
        const field = {...emptyField, type, required: true, restrictedValues: type === 'select' ? ['blue'] : []};
        const {edit, requests} = await fixture(t, field);
        await edit.locator('sl-button[type=submit]').click();
        await edit.locator('sl-dialog[open]').waitFor({state: 'hidden'});
        assert.equal(requests.length, 1);
        assert.equal(requests[0].type, type);
    });
}
