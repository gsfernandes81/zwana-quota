#!/bin/bash
# Inside the container: keys.sh command...  -- drive the running app.
#   key:X,Y   click a button on the device picture (README.md has where)
#   hold:X,Y  hold it (UP held is MENU: the debug build's next reading)
#   wait:S    sleep S seconds
#   shot:NAME the screen, cropped by $CROP, to /w/NAME.png
#   full:NAME the whole window
for c in "$@"; do
  case $c in
    key:*) xy=${c#key:}; xdotool mousemove "${xy%,*}" "${xy#*,}" click 1; sleep 1.3 ;;
    hold:*) xy=${c#hold:}; xdotool mousemove "${xy%,*}" "${xy#*,}" mousedown 1; sleep 1.6; xdotool mouseup 1; sleep 1.3 ;;
    wait:*) sleep "${c#wait:}" ;;
    shot:*) import -window root -crop "${CROP:-176x176+104+186}" +repage "/w/${c#shot:}.png" ;;
    full:*) import -window root "/w/${c#full:}.png" ;;
  esac
done
