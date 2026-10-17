# androidagent background service launcher (read by ServiceManager, never installed or sourced).
# The app runs it in the embedded userland as its own PRoot process tree:
#   bash --noprofile --norc -c "<this file>" androidagent-service NAME DIRECTORY COMMAND
# NAME, DIRECTORY and COMMAND arrive as positional arguments: nothing is interpolated into
# this script. The owner approved COMMAND verbatim; it must stay in the foreground.
name=$1
dir=$2
command=$3
case $dir in
  '') dir=$HOME ;;
  '~') dir=$HOME ;;
  '~/'*) dir=$HOME/${dir#'~/'} ;;
esac
if ! cd -- "$dir" 2>/dev/null; then
  printf '[androidagent] Brak katalogu roboczego: %s\n' "$dir" >&2
  exit 126
fi
export ANDROIDAGENT_SERVICE="$name"
exec </dev/null
# exec keeps one main process: when it exits, PRoot (--kill-on-exit) ends the whole tree.
exec bash -c "$command"
