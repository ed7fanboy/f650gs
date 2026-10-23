L=${L:-$HOME/coding/home/devices/phones/forum_app/build/landing}; mkdir -p $L/raw $L/out
SRC=$HOME/coding/home/devices/phones/forum_app/app/src/main/java
D() { adb -s R5CY40B9VNL shell "$@"; }
inapp() { D dumpsys activity activities | grep -q 'topResumedActivity.*pl.f650gs.forum' || { echo NOT_APP; return 1; }; }
dump() { D uiautomator dump /sdcard/u.xml >/dev/null; adb -s R5CY40B9VNL exec-out cat /sdcard/u.xml; }
# Taps the centre of the first node whose text or content-desc is exactly $1.
tap() { local b; b=$(dump | grep -o "\(text\|content-desc\)=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -d '[' | tr ']' ',' ); [ -z "$b" ] && { echo "no $1"; return 1; }; IFS=, read x1 y1 x2 y2 _ <<<"$b"; D input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); }
shot() { inapp || return 1; adb -s R5CY40B9VNL exec-out screencap -p > $L/raw/$1.png; dump > $L/raw/$1.xml; python3 $L/blur.py $SRC $L/raw/$1.png $L/raw/$1.xml $L/out/$1.png; }
home() { D monkey -p pl.f650gs.forum -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2; for i in 1 2 3 4; do inapp && dump | grep -q 'text="Ostatnie z forum"' && return 0; D input keyevent BACK; sleep 1; D monkey -p pl.f650gs.forum -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2; done; }
# Like tap, but only matches visible text (not icon descriptions).
taptext() { local b; b=$(dump | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | tr -d '[' | tr ']' ','); [ -z "$b" ] && { echo "no $1"; return 1; }; IFS=, read x1 y1 x2 y2 _ <<<"$b"; D input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); }
