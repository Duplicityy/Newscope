#!/usr/bin/env bash
set -euo pipefail
umask 077

root=/srv/newscape
old="$root/releases/20261119-runtime35"
next="$root/releases/20261119-runtime36-expedition-world"
game=newscape-game@live.service
cache=newscape-cache@live.service
gateway=newscape-owner-gateway.service
expected=79bbc51b5e22fb774687df4f03684f43138bbd21bf0c4d541b2fc4082b8a70d1
probe_payload=${1:?missing endpoint probe}
exec 9>"$root/.runtime36-cutover.lock"
flock -n 9

test "$(readlink -f "$root/current")" = "$old"
printf '%s  %s\n' "$expected" "$next/MANIFEST.sha256" | sha256sum --check --status
(cd "$next" && sha256sum --check --strict --status MANIFEST.sha256)
systemctl is-active --quiet "$game"
systemctl is-active --quiet "$cache"
systemctl is-active --quiet "$gateway"
backup_root="$root/backups"
install -d -m 700 -- "$backup_root"
backup_dir=$(mktemp -d "$backup_root/pre-runtime36.XXXXXXXX")
printf '%s' "$probe_payload" | base64 -d > "$backup_dir/verify-endpoint.py"

set_selection() {
    local pending="$root/.current-runtime36-$$"
    test ! -e "$pending"
    ln -sT -- "$1" "$pending"
    mv -Tf -- "$pending" "$root/current"
}
probe() {
    python3 "$backup_dir/verify-endpoint.py" "$1" --attempts "$2"
}
rollback() {
    local status=$?
    trap - EXIT
    if ((status == 0)); then return; fi
    set +e
    printf 'Cutover failed with status %d. Returning to %s.\n' "$status" "$old" >&2
    systemctl stop "$gateway"
    systemctl stop "$game" "$cache"
    if [[ "$(readlink -f "$root/current")" == "$next" ]]; then set_selection "$old"; fi
    if [[ "$(readlink -f "$root/current")" == "$old" ]] &&
        systemctl start "$cache" "$game" &&
        probe 127.0.0.1 45 &&
        systemctl start "$gateway" &&
        probe 51.38.80.2 5; then
        printf 'Prior owner endpoint restored.\n' >&2
    else
        printf 'Rollback did not verify. The state backup is at %s.\n' "$backup_dir" >&2
    fi
    exit "$status"
}
trap rollback EXIT

systemctl stop "$gateway"
systemctl stop "$game"
systemctl stop "$cache"
for service in "$game" "$cache" "$gateway"; do
    test "$(systemctl show "$service" -p ActiveState --value)" = inactive
done
test "$(systemctl show "$game" -p Result --value)" = success
echo 'Game completed its normal systemd shutdown.'

tar --create --gzip --file "$backup_dir/ubuntu-live-state.tar.gz" --directory "$root" live
tar --compare --gzip --file "$backup_dir/ubuntu-live-state.tar.gz" --directory "$root"
(cd "$backup_dir" && sha256sum ubuntu-live-state.tar.gz > STATE-ARCHIVE.sha256)
printf '%s\n' "$old" > "$backup_dir/previous-selection.txt"
accounts=$(sha256sum "$root/live/state/accounts.properties" | cut -d ' ' -f1)
printf 'Verified authoritative state backup at %s\n' "$backup_dir"

set_selection "$next"
test "$(readlink -f "$root/current")" = "$next"
systemctl start "$cache" "$game"
probe 127.0.0.1 45
invocation=$(systemctl show "$game" -p InvocationID --value)
test -n "$invocation"
journalctl --no-pager "_SYSTEMD_INVOCATION_ID=$invocation" -o cat > "$backup_dir/startup.log"
grep -F 'Charter travel: 8 of 8 ports, 12 crew placements.' "$backup_dir/startup.log"
grep -F 'World vertical travel: 166 reciprocal links, 0 skipped.' "$backup_dir/startup.log"
grep -F 'Draynor Sewers: 2 of 2 entrances bound.' "$backup_dir/startup.log"
grep -F 'Daemonheim: campfire rewards trader and expedition guide enabled.' "$backup_dir/startup.log"
grep -F 'Started server in ' "$backup_dir/startup.log"
if grep -Fq 'Error loading handlers' "$backup_dir/startup.log"; then
    echo 'The staged game reported a handler startup error.' >&2
    exit 1
fi
test "$accounts" = "$(sha256sum "$root/live/state/accounts.properties" | cut -d ' ' -f1)"

systemctl start "$gateway"
probe 51.38.80.2 5
for service in "$game" "$cache" "$gateway"; do
    test "$(systemctl show "$service" -p ActiveState --value)" = active
    printf '%s PID=%s restarts=%s\n' "$service" "$(systemctl show "$service" -p MainPID --value)" "$(systemctl show "$service" -p NRestarts --value)"
done
printf 'Current selection %s\n' "$next"
printf 'Owner state backup checksum '
cat "$backup_dir/STATE-ARCHIVE.sha256"
trap - EXIT
