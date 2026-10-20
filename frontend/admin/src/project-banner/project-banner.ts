import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js'
import { when } from 'lit/directives/when.js';
import { ConfigurationService } from '../service/configuration';
import { retroCompat, textColors, panelStyles, row, spacing } from '../styles'

@customElement('alfio-project-banner')
export class ProjectBanner extends LitElement {

    @property({ type: String, attribute: 'data-full-banner' })
    fullBanner?: 'true' | 'false';

    @property({ type: String, attribute: 'data-alfio-version' })
    alfioVersion?: string;

        static readonly styles = [
        retroCompat,
        panelStyles,
        textColors,
        row,
        spacing,
        css` :host { --alfio-row-cols: 3 }`
    ];

    render() {
        const notFullBanner = () => html`
            <div>
                <h5 class="text-muted">Powered by <a href="https://github.com/WookJaes/alf.io" target="_blank">Alf.io</a> v.${this.alfioVersion}</h5>
                <small><a href="https://github.com/WookJaes/alf.io/issues" target="_blank" rel="noopener">포크 이슈</a> · <a href="https://github.com/WookJaes/alf.io/blob/main/README.md#contributing--이-포크에-기여하기" target="_blank" rel="nofollow noopener noreferrer">기여 안내</a></small>
                <div class="wMarginTop10px">
                    <sl-button variant="success" size="small" href="https://github.com/WookJaes/alf.io" target="_blank" rel="noopener">포크 저장소</sl-button>
                </div>
            </div>
        `;

        const fullBanner = () => html`
            <div>
                <h3 class="text-center">Alf.io 기반 독립 포크입니다.</h3>
                <h4> 이 포크는 코드 수정·검증을 목적으로 합니다.<br>
                앱·Docker 이미지·웹사이트를 별도로 배포하지 않습니다. </h4>
                <div>&nbsp;</div>
                <div class="row">
                    <div>
                        <sl-button variant="primary" href="https://github.com/WookJaes/alf.io" target="_blank" rel="noopener">
                            포크 저장소
                        </sl-button>
                    </div>
                    <div>
                        <sl-button variant="success" href="https://github.com/WookJaes/alf.io/issues" target="_blank" rel="noopener">
                            포크 이슈
                        </sl-button>
                    </div>
                    <div>
                        <sl-button type="button" variant="default" @click=${this.dismiss}>닫기</sl-button>
                    </div>
                </div>
            </div>
        `;

        return html`${when(this.fullBanner === 'true', fullBanner, notFullBanner)}`;
    }

    async dismiss() {
        try {
            await ConfigurationService.update({ key: 'SHOW_PROJECT_BANNER', value: 'false' });
            this.fullBanner = 'false';
            window.location.reload();
        } catch (e) {
            console.log('error while updating...', e);
        }
    }
}

declare global {
    interface HTMLElementTagNameMap {
        'alfio-project-banner': ProjectBanner
    }
}
