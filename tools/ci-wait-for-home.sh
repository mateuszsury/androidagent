#!/usr/bin/env bash
# sys.boot_completed precedes the first usable launcher window on fresh AVDs.
# Wait for real focus before sending input or resizing the display. An existing
# system error dialog must fail this preflight rather than cover later UI tests.
set -euo pipefail
adb_command="${ADB:-adb}"
deadline=$((SECONDS + 120))
stable=0
component=''
windows=''
while (( SECONDS < deadline )); do
  if ! component=$(timeout 10 "$adb_command" shell cmd package resolve-activity --brief \
      -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1) \
      || ! windows=$(timeout 10 "$adb_command" shell dumpsys window); then
    stable=0
    sleep 2
    continue
  fi
  focus=$(printf '%s\n' "$windows" | grep 'mCurrentFocus=' || true)
  if [[ "$component" == */* && "$component" != *FallbackHome* && "$component" != *setup* \
        && "$focus" == *"${component%%/*}/"* ]]; then
    stable=$((stable + 1))
    if (( stable >= 2 )); then
      printf 'Emulator home is focused: %s\n' "$focus"
      exit 0
    fi
  else
    stable=0
  fi
  sleep 2
done
printf 'Emulator home did not gain stable focus; resolved home: %s\n%s\n' "$component" "$windows" >&2
"$adb_command" shell dumpsys activity lastanr >&2 || true
exit 1
