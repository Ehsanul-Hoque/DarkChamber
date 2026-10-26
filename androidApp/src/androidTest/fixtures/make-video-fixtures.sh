#!/bin/bash
# Makes the clips in ../assets/video/ that VideoThumbnailInstrumentedTest reads. Made by ffmpeg, never
# by this app, so a bug in the app's own recorder cannot hide in them. Run from this directory:
#   ./make-video-fixtures.sh ../assets/video
set -euo pipefail
A="$1"
mkdir -p "$A"
Q=(-hide_banner -loglevel error -y)
BLACK_THEN_RED="drawbox=x=0:y=0:w=iw:h=ih:color=black:t=fill:enable='lt(t,0.5)',format=yuv420p"

# 3 s: black for 0.5 s, then red. A keyframe every 0.5 s. moov at the end (ffmpeg's default).
ffmpeg "${Q[@]}" -f lavfi -i "color=c=red:s=320x240:r=30:d=3" -vf "$BLACK_THEN_RED" \
  -c:v libx264 -g 15 -keyint_min 15 -sc_threshold 0 "$A/colour.mp4"
# The same with moov at the start.
ffmpeg "${Q[@]}" -f lavfi -i "color=c=red:s=320x240:r=30:d=3" -vf "$BLACK_THEN_RED" \
  -c:v libx264 -g 15 -keyint_min 15 -sc_threshold 0 -movflags +faststart "$A/colour-faststart.mp4"
# The same picture with key frames only at 0 s (black) and 2.4 s (red): the key frame *after* a
# second is red, the *nearest* to it is the black one, so the two choices give different previews.
ffmpeg "${Q[@]}" -f lavfi -i "color=c=red:s=320x240:r=30:d=3" -vf "$BLACK_THEN_RED" \
  -c:v libx264 -g 72 -keyint_min 72 -sc_threshold 0 -bf 0 "$A/sparse-keys.mp4"
# 320x240, red left half, blue right half, tagged to be shown turned 90 degrees clockwise.
ffmpeg "${Q[@]}" -f lavfi -i "color=c=red:s=160x240:r=30:d=2" -f lavfi -i "color=c=blue:s=160x240:r=30:d=2" \
  -filter_complex "[0][1]hstack,format=yuv420p" -c:v libx264 -g 15 "$A/rotated-plain.mp4"
ffmpeg "${Q[@]}" -display_rotation:v:0 -90 -i "$A/rotated-plain.mp4" -c copy "$A/rotated.mp4"
rm "$A/rotated-plain.mp4"
# 0.3 s of green: shorter than the 1 s the frame is normally taken at.
ffmpeg "${Q[@]}" -f lavfi -i "color=c=green:s=320x240:r=30:d=0.3" -vf format=yuv420p -c:v libx264 "$A/short.mp4"
# No video track at all.
ffmpeg "${Q[@]}" -f lavfi -i "sine=frequency=440:duration=2" -c:a aac "$A/audio-only.mp4"
