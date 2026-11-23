#!/bin/bash
# Vuelve a guardar los videos de noche: se suavizan los escalones del negro y quedan en 10 bits (más tonos de oscuro).
set -e
parte=$1; partes=$2
gh release view mar-v3 --json assets -q '.assets[].name' | grep -E 'noche' | grep -E '\.mp4$' | grep -v '\.d10\.' | sort > lista.txt
i=0
while read -r f; do
  if [ $((i % partes)) -eq $parte ]; then
    gh release download mar-v3 -p "$f" -O orig.mp4 --clobber
    ffmpeg -nostdin -v error -y -i orig.mp4 \
      -vf "format=yuv420p16le,deband=1thr=0.012:2thr=0.012:3thr=0.012:4thr=0.012:range=18:blur=1,format=yuv420p10le" \
      -c:v libx265 -preset medium -crf 17 -x265-params "profile=main10:aq-mode=3:aq-strength=1.1:psy-rd=1.5:keyint=60:log-level=error" \
      -tag:v hvc1 -an -movflags +faststart "${f%.mp4}.d10.mp4"
    echo "$f $(stat -c%s orig.mp4) -> $(stat -c%s ${f%.mp4}.d10.mp4)"
    gh release upload mar-v3 "${f%.mp4}.d10.mp4" --clobber
    rm -f orig.mp4 "${f%.mp4}.d10.mp4"
  fi
  i=$((i+1))
done < lista.txt
