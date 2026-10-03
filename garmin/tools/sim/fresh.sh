#!/bin/bash
# Inside the container: fresh.sh <device> <prg> <x,y of the notice's OK>
# A new simulator, the app loaded in it, and the "no data connection" notice
# (the app registers for phone messages on opening) dismissed. Normal launch
# mode, not the glance loop: Glance=0 in simulator.ini.
set -u
B=$(cat /root/.Garmin/ConnectIQ/current-sdk.cfg)/bin
kill -9 $(pgrep -f "^/root/.*bin/simulator$") $(pgrep -f "monkeydo|monkeybrains") 2>/dev/null
sleep 2
sed -i 's/^Glance=1/Glance=0/' /root/.Garmin/ConnectIQ/simulator.ini 2>/dev/null
setsid "$B/simulator" > /w/sim.log 2>&1 < /dev/null &
sleep 8
: > /w/do.log
setsid timeout 3600 "$B/monkeydo" "$2" "$1" > /w/do.log 2>&1 < /dev/null &
for _ in $(seq 60); do
  sleep 1
  xdotool search --name "^Error$" >/dev/null 2>&1 && break
done
sleep 1
xdotool mousemove "${3%,*}" "${3#*,}" click 1
sleep 2
