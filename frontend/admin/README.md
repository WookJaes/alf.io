# Frontend for admin

Use lit element + shoelace style.

Created with vite: `pnpm create vite@latest admin -- --template lit-ts`

See https://vitejs.dev/guide/backend-integration.html

See https://stackoverflow.com/a/76446465 for icon/static assets

## run dev server

`pnpm run dev`

Then you can launch alf.io normally and go to localhost:8080/admin/ -> all the components exported in the main.ts will be available.

## todo

 - find a decent way to inline the icons as data uri

 ## vscode plugins

 - https://marketplace.visualstudio.com/items?itemName=runem.lit-plugin


## Attendee reconciliation browser regression tests

Use Node.js 22.22.3 and pnpm 11.1.2, matching the repository's Gradle configuration. Run these commands from the repository root:

```sh
pnpm --dir frontend/admin install --frozen-lockfile
pnpm --dir frontend/admin exec playwright install chromium
pnpm --dir frontend/admin run test:reconciliation
```

Playwright is pinned as a development dependency and uses its matching Chromium build. No absolute Chrome path or external Playwright module path is required on macOS, Windows, or Linux. On Linux, add `--with-deps` to the browser installation command if system dependencies are also needed. Set `CHROME_PATH` only when you explicitly want to use another installed Chrome executable.

The 14 tests use the actual Lit component with synthetic API responses to verify successful and failed filter and page changes, selection and edited draft preservation, comparison switching, deletion, and copying. They start a temporary Vite server and isolated browser contexts without connecting to a real login session, database, or external AI service. Use the repository's `e2eValidation` task for application and database integration tests.

The `Browser regression` jobs in the `Local E2E validation` workflow run the same installation and test steps on Ubuntu and Windows. They run separately from the existing Java E2E job, and a test failure causes the corresponding job to fail.
