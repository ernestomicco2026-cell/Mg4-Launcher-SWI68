# MG4 Launcher (SWI68) — Project Instructions

## How to Respond
- Always respond in English, using clear language and simple explanations.
- If something is missing (a file, an APK to analyze, or a tool), stop. Do not make things up.
- When asked to explain, only eplain. Do not change code
- When asked to code, first state a plan. Only start coding when the developer has approved the change.
- Interpret approval from the developer's wording and the conversation context. Clear commands such as "make the change", "go ahead", or "implement it" count as approval for the discussed change; the developer does not need to say exactly "Yes, I approve". If approval is already clear, proceed without asking again. Ask for approval and feedback only when permission or scope is unclear.
- Always document code in simple terms, in english and italian.

## Git and GitHub
- Nothing goes on GitHub (code, APKs, README, or `releases/latest.json`) without explicit approval.
- When asked to commit, review the changes and propose a clear, descriptive commit message to the developer. Wait for approval of the proposed message before creating the commit. The initial request to commit does not itself approve the message.
- When asked to push, tell the developer which remote and branch will be pushed, and list the commits to be pushed with their short hashes and messages. Wait for approval of those push details before pushing. The initial request to push does not itself approve the push.
- Commit approval and push approval are separate: approval to commit does not authorize pushing.
- Do not modify old APKs in `releases/` or existing GitHub Releases: keep all of them.

## Keeping Navigation Branches in Sync
- When adding an icon or changing code on `navigation-maneuver-icons`, also apply and commit the corresponding change to `offline-navigation-maps`. Include all required supporting files and preserve the existing offline-map functionality.
- Follow the commit-message approval process for both branches. If a change does not apply cleanly, explain the conflict and propose an adaptation before changing its behavior. Do not push either branch without separate push approval.

## Secrets and Files Excluded from Git
- `tools/android.jar`, `tools/d8.jar`, `tools/apksig.jar`, `tools/log-token.txt`, and all tokens are excluded from Git
  (see `.gitignore`): never commit them, print them, or write them into repository files.
- Read the logging token from `tools/log-token.txt` or the `MG4_LOG_TOKEN` environment variable.

## Investigating Logs
- When asked to investigate logs, first pull the latest changes from `sommistica/temp-mg4-debug` into the local `temp-mg4-debug/` checkout. Use a fast-forward-only pull; do not overwrite local changes. If the pull fails, report it rather than treating the local logs as current.
- Inspect only the newest relevant logs, prioritizing files newly received by that pull and the latest test session. Use the dates and times in log paths and headers, not local file modification times. Follow any date or time range specified by the developer.
- State which log dates and times were inspected. Do not expand to older logs unless the developer requests it.

## Location Lookups for Navigation Tests
- The developer explicitly permits sending locations and addresses they supply for navigation testing to public geocoding and routing services, including PDOK and OSRM, to find nearby test destinations and verify route distances and maneuvers. Do not ask again for the same authorized purpose unless the scope changes or permission is withdrawn.
- This permission covers those lookup requests only; do not add the home address or coordinates to tracked files, diagnostic logs, or reports. Reference the address from the conversation rather than storing it here.
- This records the developer's consent; it does not disable sandbox restrictions or automatic approval review. If a lookup was previously rejected for lack of consent, include this explicit permission when requesting review again.

## README
- Maintain the changelog in Italian and English (the "Versioni" and "Changelog" sections).
- Use version numbers only, without dates.

## For Every New Version
- Increment `VERSION_CODE` and `VERSION_NAME` in `version.properties`.
- Update the README changelog in both Italian and English.

## Variants
- Keep the appearance identical across SWI68, SWI69, SWI132, and SWI133.

## How to Build
- Generate an APK only when the developer explicitly asks for one. If an APK seems useful or necessary, explain why and ask for approval before building. Approval to change code or run tests does not authorize APK generation.
Use build-gijsje.sh, first time in spect in, then update this agents.md file with what you hav found.

- The custom script is `.local-build/build-gijsje.sh`; `.local-build/build-debug.ps1` runs it in the persistent Docker container `mg4-launcher-debug-builder`.
- The script clears and recreates `build/`. Preserve any APKs needed for comparison outside that folder before building.
- Custom builds support version overrides, use the existing signing key, and bundle the log destination from `tools/log-repo.txt`. The Docker configuration skips the bundled Store and adds `-debug` to the version name.

- Requires Java (JDK 17 or newer), `python3`, `zip`, `unzip`, and `curl`.
- The version is read from `version.properties`.
- The APK is generated at `build/MG4Launcher-<VERSION_NAME>.apk` (the `build/` directory is excluded from Git).
