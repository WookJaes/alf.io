import {css, html, LitElement, nothing} from 'lit';
import {customElement, property, state} from 'lit/decorators.js';
import {retroCompat} from '../../styles.ts';

type Finding = 'CSV_ONLY' | 'SYSTEM_ONLY' | 'DUPLICATE' | 'EMAIL_MISMATCH';
type Filter = 'ALL' | 'ATTENTION' | 'MATCHED' | Finding;
interface Summary {
    csvRows: number; csvTickets: number; systemTickets: number;
    matchedTickets: number; attentionTickets: number; findings: Record<Finding, number>;
}
interface Header { comparisonId: string; comparedAt: string; expiresAt: string; summary: Summary; aiAvailable: boolean; }
interface Item {
    reference: string; uuid: string; csvRows: {rowNumber: number; email: string}[];
    system: {email: string | null; status: string; reservationId: string} | null;
    findings: Finding[]; emailMissing: boolean;
}
interface Page { header: Header; page: number; pageSize: number; total: number; items: Item[]; }
interface DraftEntry { reference: string; explanation: string; nextStep: string; draft: string; }
interface Draft { entries: DraftEntry[]; model: string; inputTokens: number; cachedInputTokens: number; outputTokens: number; durationMillis: number; }
const labels: Record<Filter, string> = {
    ALL: '전체', ATTENTION: '확인 필요', MATCHED: '정상 일치', CSV_ONLY: 'CSV에만 있음',
    SYSTEM_ONLY: '시스템에만 있음', DUPLICATE: 'CSV 중복', EMAIL_MISMATCH: '이메일 불일치'
};
const errors: Record<string, string> = {
    FILE_TOO_LARGE: '파일은 5MiB 이하로 준비해 주세요.', EMPTY_CSV: '헤더와 데이터가 있는 CSV를 선택해 주세요.',
    MISSING_COLUMNS: 'ID와 E-Mail 열을 포함해 다시 내보내 주세요.', INVALID_HEADERS: '빈 헤더와 중복 헤더를 수정해 주세요.',
    INVALID_ID: 'ID를 표준 티켓 UUID로 수정해 주세요.', COLUMN_COUNT: '헤더와 데이터의 열 개수를 맞춰 주세요.',
    INVALID_CSV: 'UTF-8 CSV의 구분자와 따옴표 형식을 확인해 주세요.', INVALID_FIELD: '필드 길이(4,096자)와 제어 문자를 확인해 주세요.',
    TOO_MANY_ROWS: '데이터가 20,000레코드 이하인 전체 비교용 명단을 준비해 주세요.',
    TOO_MANY_TICKETS: '첫 버전은 비교 대상 티켓 20,000개 이하 행사를 지원합니다.', RESULT_TOO_LARGE: '비교 근거 크기가 제한을 초과했습니다.',
    STORAGE_NOT_CONFIGURED: '서버 관리자에게 비교 결과 암호화 키 설정을 요청해 주세요.', STORAGE_UNAVAILABLE: '저장 결과를 읽을 수 없습니다. 서버의 암호화 설정을 확인해 주세요.',
    STORAGE_LIMIT: '저장 한도에 도달했습니다. 이전 비교 결과를 삭제한 뒤 다시 시도해 주세요.',
    COMPARISON_NOT_FOUND: '결과가 만료·삭제되었거나 접근할 수 없습니다. 명단을 다시 비교해 주세요.',
    AI_NOT_CONFIGURED: 'AI 연결이 설정되지 않았습니다. 비교 결과는 계속 확인할 수 있습니다.',
    AI_FAILED: 'AI 연결에 실패했습니다. 비교 결과를 확인하고 나중에 다시 시도해 주세요.',
    AI_TIMEOUT: 'AI 응답 시간이 초과되었습니다. 비교 결과는 유지됩니다. 다시 시도해 주세요.',
    AI_INVALID_RESPONSE: 'AI 응답이 근거 검증을 통과하지 못했습니다. 초안 생성을 다시 시도해 주세요.',
    AI_RATE_LIMIT: 'AI 호출 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.', AI_BUSY: '이 결과의 초안을 생성 중입니다. 잠시 후 다시 시도해 주세요.',
    INVALID_SELECTION: '확인이 필요한 항목을 중복 없이 1~20개 선택해 주세요.'
};

@customElement('alfio-attendee-reconciliation')
export class AttendeeReconciliation extends LitElement {
    static styles = [retroCompat, css`
        :host { display: block; }
        .panel { max-width: 1100px; margin: 0 auto; padding: 24px; }
        .notice, .summary { background: #f3f6fa; padding: 16px; border-radius: 6px; margin: 16px 0; }
        .toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin: 16px 0; }
        .error { color: #a32020; background: #fff1f1; padding: 12px; }
        .success { color: #206020; }
        .item { border: 1px solid #d5dce4; border-radius: 6px; padding: 16px; margin: 12px 0; overflow-wrap: anywhere; }
        .tags { color: #7d4914; }
        .table-wrap { overflow-x: auto; }
        table { width: 100%; border-collapse: collapse; margin: 12px 0; }
        th, td { text-align: left; border-bottom: 1px solid #d5dce4; padding: 8px; }
        textarea { width: 100%; min-height: 130px; padding: 12px; box-sizing: border-box; }
        button, select, input { font: inherit; }
        button { cursor: pointer; padding: 8px 12px; }
        button:disabled { cursor: default; opacity: .55; }
        label { display: inline-flex; align-items: center; gap: 6px; }
        small { display: block; margin-top: 8px; color: #4b5563; }
    `];
    @property({attribute: 'data-event-name'}) eventName = '';
    @state() private file: File | null = null;
    @state() private history: Header[] = [];
    @state() private result: Page | null = null;
    @state() private filter: Filter = 'ATTENTION';
    @state() private selected = new Set<string>();
    @state() private draft: Draft | null = null;
    @state() private busy = false;
    @state() private error = '';
    @state() private copied = '';
    private version = 0;
    private controller = new AbortController();

    protected updated(changed: Map<string, unknown>) {
        if (changed.has('eventName')) {
            this.cancel(); this.result = null; this.history = []; this.file = null; this.draft = null; this.selected = new Set();
            this.error = ''; this.busy = false;
            if (this.eventName) void this.refreshHistory();
        }
    }
    disconnectedCallback() { this.cancel(); super.disconnectedCallback(); }
    private cancel() { this.version++; this.controller.abort(); this.controller = new AbortController(); }
    private get base() { return `/admin/api/events/${encodeURIComponent(this.eventName)}/attendee-reconciliations`; }
    private async request<T>(url: string, method = 'GET', body?: BodyInit, csv = false): Promise<T> {
        const header = document.querySelector('meta[name=_csrf_header]')?.getAttribute('content');
        const token = document.querySelector('meta[name=_csrf]')?.getAttribute('content');
        const headers: Record<string, string> = {'Accept': 'application/json'};
        if (method !== 'GET' && header && token) headers[header] = token;
        if (body) headers['Content-Type'] = csv ? 'text/csv;charset=UTF-8' : 'application/json';
        const response = await fetch(url, {method, body, headers, credentials: 'include', cache: 'no-store', signal: this.controller.signal});
        if (!response.ok) {
            let message = response.status === 403 ? '이 행사 또는 비교 결과에 접근할 권한이 없습니다.' :
                response.status === 401 ? '로그인 후 다시 시도해 주세요.' : '요청을 처리하지 못했습니다. 다시 시도해 주세요.';
            try {
                const value = await response.json();
                if (errors[value.code]) message = `${value.rowNumber ? `CSV 레코드 ${value.rowNumber}: ` : ''}${errors[value.code]}`;
            } catch { /* Never display upstream bodies or exception messages. */ }
            throw new Error(message);
        }
        return response.status === 204 ? undefined as T : response.json();
    }
    private async refreshHistory() {
        const version = this.version;
        try {
            const history = await this.request<Header[]>(this.base);
            if (version === this.version) this.history = history;
        } catch (e) { if (version === this.version) this.error = this.message(e); }
    }
    private message(e: unknown) {
        if (e instanceof TypeError) return '파일이 변경되었거나 연결에 실패했습니다. CSV를 다시 선택하고 시도해 주세요.';
        return e instanceof Error ? e.message : '연결을 확인하고 다시 시도해 주세요.';
    }
    private async operation(action: (version: number) => Promise<void>, clearDraft = true) {
        this.cancel(); const version = this.version;
        this.busy = true; this.error = ''; this.copied = '';
        if (clearDraft) { this.draft = null; this.selected = new Set(); }
        try { await action(version); }
        catch (e) { if (version === this.version) this.error = this.message(e); }
        finally { if (version === this.version) this.busy = false; }
    }
    private async load(id: string, page = 0) {
        await this.operation(async version => {
            const result = await this.request<Page>(`${this.base}/${encodeURIComponent(id)}?filter=${this.filter}&page=${page}`);
            if (version === this.version) this.result = result;
        });
    }
    private async compare() {
        if (!this.file) return;
        const file = this.file;
        await this.operation(async version => {
            const header = await this.request<Header>(this.base, 'POST', file, true);
            if (version !== this.version) return;
            this.filter = 'ATTENTION';
            const result = await this.request<Page>(`${this.base}/${header.comparisonId}?filter=ATTENTION`);
            if (version === this.version) { this.result = result; await this.refreshHistory(); }
        });
    }
    private async generate() {
        if (!this.result || this.selected.size === 0) return;
        const id = this.result.header.comparisonId, references = [...this.selected];
        await this.operation(async version => {
            const draft = await this.request<Draft>(`${this.base}/${id}/drafts`, 'POST', JSON.stringify({references}));
            if (version === this.version) this.draft = draft;
        }, false);
    }
    private toggle(reference: string, checked: boolean) {
        const selected = new Set(this.selected);
        if (checked && selected.size >= 20) { this.error = errors.INVALID_SELECTION; return; }
        if (checked) selected.add(reference); else selected.delete(reference);
        this.selected = selected; this.draft = null; this.copied = '';
    }
    private async deleteComparison() {
        if (!this.result) return;
        const id = this.result.header.comparisonId;
        await this.operation(async version => {
            await this.request<void>(`${this.base}/${id}`, 'DELETE');
            if (version === this.version) { this.result = null; await this.refreshHistory(); }
        });
    }
    private async copy(entry: DraftEntry) {
        try { await navigator.clipboard.writeText(entry.draft); this.copied = entry.reference; }
        catch { this.error = '복사 권한이 없습니다. 초안의 텍스트를 직접 선택해 복사해 주세요.'; }
    }
    private date(value: string) { return new Date(value).toLocaleString(); }
    render() {
        const result = this.result;
        return html`<section class="panel" aria-busy=${this.busy}>
            <h2>명단 비교와 안내 초안</h2>
            <div class="notice">선택한 행사의 ACQUIRED · CHECKED_IN · TO_BE_PAID 티켓을 비교합니다.
                <br>ID는 티켓 UUID, E-Mail은 참석자 이메일입니다. 결제 완료 여부나 동일인을 추측하지 않습니다.
                <small>UTF-8 CSV · ID/E-Mail 필수 · 최대 5MiB/20,000레코드. 결과는 암호화해 24시간 보관합니다.
                    AI에는 개인정보를 보내지 않습니다. 이메일 발송과 예약 변경은 수행하지 않습니다.</small>
            </div>
            <div class="toolbar"><label>CSV 파일 <input id="csv-file" type="file" accept=".csv,text/csv" ?disabled=${this.busy}
                @change=${(e: Event) => { const input = e.target as HTMLInputElement; this.file = input.files?.[0] ?? null; input.value = ''; this.error = ''; }}></label>
                ${this.file ? html`<span>선택한 파일: ${this.file.name}</span>` : nothing}
                <button id="compare" ?disabled=${this.busy || !this.file || this.file.size > 5 * 1024 * 1024} @click=${this.compare}>명단 비교</button>
                ${this.file && this.file.size > 5 * 1024 * 1024 ? html`<span>${errors.FILE_TOO_LARGE}</span>` : nothing}
            </div>
            ${this.history.length ? html`<div class="toolbar"><label>저장된 비교 <select id="history" ?disabled=${this.busy}
                @change=${(e: Event) => { const id = (e.target as HTMLSelectElement).value; if (id) void this.load(id); }}>
                <option value="" .selected=${!this.result}>비교 결과 선택</option>${this.history.map(h => html`<option value=${h.comparisonId} .selected=${this.result?.header.comparisonId === h.comparisonId}>
                    ${this.date(h.comparedAt)} · 확인 필요 ${h.summary.attentionTickets}개</option>`)}
                </select></label><button ?disabled=${this.busy} @click=${this.refreshHistory}>목록 새로고침</button></div>` : nothing}
            ${this.error ? html`<p class="error" role="alert">${this.error}</p>` : nothing}
            ${this.busy ? html`<p role="status">처리 중입니다…</p>` : nothing}
            ${result ? html`<div class="summary" id="summary">
                <strong>정상 일치 ${result.header.summary.matchedTickets}개 · 확인 필요 ${result.header.summary.attentionTickets}개</strong>
                <p>CSV ${result.header.summary.csvRows}레코드 / ${result.header.summary.csvTickets}티켓 · 시스템 ${result.header.summary.systemTickets}티켓</p>
                <p>${Object.entries(result.header.summary.findings).map(([type, count]) => html`<span>${labels[type as Finding]} ${count} · </span>`)}</p>
                <small>유형별 건수는 중복 집계될 수 있습니다. 비교: ${this.date(result.header.comparedAt)} / 만료: ${this.date(result.header.expiresAt)}.
                    이후 시스템 변경은 재비교해야 합니다.</small>
            </div><div class="toolbar"><label>결과 필터 <select id="filter" ?disabled=${this.busy}
                @change=${(e: Event) => { this.filter = (e.target as HTMLSelectElement).value as Filter; void this.load(result.header.comparisonId); }}>
                ${Object.entries(labels).map(([value, label]) => html`<option value=${value} .selected=${value === this.filter}>${label}</option>`)}</select></label>
                <button id="generate" ?disabled=${this.busy || !result.header.aiAvailable || this.selected.size === 0} @click=${this.generate}>
                    선택 ${this.selected.size}개 설명·초안 생성</button>
                <button id="delete-comparison" ?disabled=${this.busy} @click=${this.deleteComparison}>현재 비교 삭제</button>
            </div>${!result.header.aiAvailable ? html`<p>AI 연결이 설정되지 않았습니다. 비교 결과는 계속 확인할 수 있습니다.</p>` : nothing}
            <p>필터 결과 ${result.total}개 · CSV 레코드 번호는 헤더를 1로 셉니다. 따옴표 안의 줄바꿈은 같은 레코드입니다.</p>
            ${result.items.map(item => this.renderItem(item))}
            <div class="toolbar"><button id="previous" ?disabled=${this.busy || result.page === 0}
                @click=${() => this.load(result.header.comparisonId, result.page - 1)}>이전</button>
                <span>${result.page + 1} / ${Math.max(1, Math.ceil(result.total / result.pageSize))}</span>
                <button id="next" ?disabled=${this.busy || (result.page + 1) * result.pageSize >= result.total}
                @click=${() => this.load(result.header.comparisonId, result.page + 1)}>다음</button></div>` : nothing}
            ${this.draft ? html`<section id="drafts"><h3>AI가 검증된 표현으로 구성한 안내 초안</h3>
                <p>선택 항목만 설명합니다. 근거를 확인하고 문구를 수정한 뒤 사용하세요. 수정한 초안은 브라우저에만 있으며 새로고침 시 사라집니다.</p>
                <small>모델 ${this.draft.model} · 입력 ${this.draft.inputTokens}토큰(캐시 ${this.draft.cachedInputTokens}) · 출력 ${this.draft.outputTokens}토큰 · ${this.draft.durationMillis}ms.
                    실제 청구액은 공급자의 사용 내역을 확인하세요.</small>
                ${this.draft.entries.map(entry => html`<article class="item"><h4>${entry.reference}</h4>
                    <p>${entry.explanation}</p><p>${entry.nextStep}</p>
                    <label for=${`draft-${entry.reference}`}>안내 초안</label>
                    <textarea id=${`draft-${entry.reference}`} .value=${entry.draft}
                        @input=${(e: Event) => { entry.draft = (e.target as HTMLTextAreaElement).value; this.copied = ''; }}></textarea>
                    <button @click=${() => this.copy(entry)}>초안 복사</button>
                    ${this.copied === entry.reference ? html`<span class="success" role="status">복사했습니다.</span>` : nothing}
                </article>`)}</section>` : nothing}
        </section>`;
    }
    private renderItem(item: Item) {
        return html`<article class="item" data-reference=${item.reference}>
            <label><input type="checkbox" .checked=${this.selected.has(item.reference)}
                ?disabled=${this.busy || item.findings.length === 0 || (!this.selected.has(item.reference) && this.selected.size >= 20)}
                @change=${(e: Event) => this.toggle(item.reference, (e.target as HTMLInputElement).checked)}>
                <strong>${item.reference} · ${item.uuid}</strong></label>
            <p class="tags">${item.findings.length ? item.findings.map(f => labels[f]).join(' · ') : '정상 일치'}${item.emailMissing ? ' · 이메일 미등록' : ''}</p>
            <details><summary>판정 근거 보기</summary><div class="table-wrap"><table>
                <thead><tr><th>출처</th><th>참석자 이메일</th><th>시스템 정보</th></tr></thead><tbody>
                ${item.csvRows.map(row => html`<tr><td>CSV 레코드 ${row.rowNumber}</td><td>${row.email || '(미등록)'}</td><td>—</td></tr>`)}
                ${item.system ? html`<tr><td>시스템</td><td>${item.system.email || '(미등록)'}</td><td>${item.system.status} · 예약 ${item.system.reservationId}</td></tr>` :
                    html`<tr><td>시스템</td><td colspan="2">선택한 행사의 비교 대상에 없음</td></tr>`}
                ${!item.csvRows.length ? html`<tr><td>CSV</td><td colspan="2">해당 티켓 ID 없음</td></tr>` : nothing}
                </tbody></table></div></details>
        </article>`;
    }
}
