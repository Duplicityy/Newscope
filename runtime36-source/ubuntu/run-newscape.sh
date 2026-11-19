#!/usr/bin/env bash
set -euo pipefail

role=${1:?missing game or cache role}
instance=${2:?missing trial or live instance}
case "$instance" in trial|live) ;; *) exit 64 ;; esac
release=$(cd -- "$(dirname -- "$0")" && pwd -P)
state="/srv/newscape/$instance/state"
home="/srv/newscape/$instance/home"
java=/usr/lib/jvm/java-8-openjdk-amd64/bin/java

cd -- "$release/workdir"
test -f "$state/accounts.properties"
common=(
    -Dkronos.bind=127.0.0.1 -Dkronos.public=false -Dkronos.registration=false
    "-Duser.home=$home" "-Dkronos.accounts=$state/accounts.properties"
    -Xms128m -XX:+UseParallelGC -XX:GCTimeRatio=9 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20
)

case "$role" in
    cache)
        cp="$release/cache-overlay/current:$release/cache-overlay/dungeoneering:$release/lib/cache/*"
        exec "$java" "${common[@]}" -Xmx256m -cp "$cp" io.ruin.update.Server
        ;;
    game)
        cp="$release/game-overlay/slayer-fury:$release/game-overlay/current:$release/game-overlay/dungeoneering"
        cp+=":$release/game-overlay/six-systems:$release/game-overlay/endgame-armor:$release/game-overlay/home:$release/game-overlay/chat-rank:$release/lib/game/*"
        exec "$java" "${common[@]}" -Xmx3584m \
            -Dkronos.universalPets=true -Dnewscape.ownerAutomation=true -Dnewscape.raids.enabled=true \
            -Dkronos.dungeoneering.enabled=true -Dkronos.dungeoneering.staffTesting=true -Dkronos.homeTesting=true \
            -Dkronos.endgameArmor.enabled=true -Dkronos.six.mastery=true -Dkronos.six.armor=true \
            -Dkronos.six.legend=true -Dkronos.six.pets=true -Dkronos.six.athletics=true \
            -Dkronos.six.athleticsSpeed=true -Dkronos.six.thanks=true -Dkronos.six.cosmeticIcons=true \
            -Dkronos.durableSaveTimeoutMillis=250 "-Dkronos.six.data=$state/six-systems" \
            "-Dkronos.playerData=$state/player-data" "-Dkronos.adventurers.config=$state/adventurers.properties" \
            -cp "$cp" io.ruin.Server
        ;;
    *) exit 64 ;;
esac
