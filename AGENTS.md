# Action Relay agent instructions

This repository is the writable project. A separate ChatGPT project `sources/` mirror is read-only and is not the working checkout.

## Scope and truth

- Target: mainland Xiaomi Watch S4 3.201.79, Xiaomi 17 / Android 17, DJI Action 4.
- User tested v0.2 basic recording through the phone relay. Stop-confirmation latency/stale UI remains a reported bug.
- Watch UI is the priority: large graphical capture control, short status, Osmo remote-inspired layout. Phone UI uses miuix-inspired native styling; do not claim the Compose Miuix library is integrated.
- GPS now explicitly belongs in scope, from the phone's real GNSS. Preserve measurement accuracy and freshness; never invent missing fields.
- Keep transport acceptance, camera state, and user-visible command confirmation separate. Unknown/transitional states are not proof that recording stopped.
- Never toggle the camera shutter blindly or automatically replay a start command. Stop must be able to supersede an unconfirmed start.

## Shared work

- User preference: work in this local repository, validate changes, then commit and push finished work to GitHub.
- Save every Copilot review checklist as its own dated Markdown file under `docs/copilot-checklists/`, push it with Git, and link the repository file in the reply. Preserve earlier checklists.
- The user authorizes calling the locally installed VS Code GitHub Copilot agent for this project's coding work. Use `scripts/start-copilot.ps1 -TaskFile <task.md>` to hand off a bounded task without asking the user to open the start page. Read `docs/copilot-workflow.md` first. Verify the returned result file and actual changes before claiming completion; do not dispatch duplicate workers or write the same files concurrently.
- Read COPILOT_HANDOFF.md for current ownership and unfinished integration. Keep one writer per file.
- Use the current development branch; main/v0.2.0 is the rollback baseline. Do not reset or discard another agent's working changes.
- Keep handoffs concise: changed files, exact tests run, unresolved hardware checks. Update COPILOT_STATUS.md at the start and end of a delegated task.
- Signing material is local in ignored .signing/ and watch/sign/. Never print private keys or commit keystores, credentials, personal GPS traces, or raw personal logs.

## Validation

- Run scripts/test.ps1 for relevant tests and scripts/build-local.ps1 for final APK/RPK integration.
- Use the same local certificate for both packages; verify APK signatures and matching package/certificate metadata.
- A build or mock test is not hardware proof. Report camera stop/save behavior, phone-watch communication, phone GNSS quality and GPS data embedded in video as separate hardware checks.
- Avoid unbounded status/telemetry queues. Coalesce old GPS/status samples; prioritize control and terminal results.
- For exported UI previews clearly label them as layout previews unless rendered by the actual runtime.
