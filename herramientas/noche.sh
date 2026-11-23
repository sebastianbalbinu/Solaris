#!/bin/bash
# Los videos de noche en 10 bits llevan dicho su color (BT.709), como lo asume el celular con los de 8 bits:
# así el azul de la noche no cambia de tono al pasar de uno a otro.
set -e
parte=$1; partes=$2
gh release view mar-v3 --json assets -q '.assets[].name' | grep -E '\.d10\.mp4$' | sort > lista.txt
i=0
while read -r f; do
  if [ $((i % partes)) -eq $parte ]; then
    gh release download mar-v3 -p "$f" -O orig.mp4 --clobber
    ffmpeg -nostdin -v error -y -i orig.mp4 -c copy \
      -bsf:v "hevc_metadata=colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1:video_full_range_flag=0" \
      -tag:v hvc1 -movflags +faststart "$f"
    ffprobe -v error -select_streams v -show_entries stream=color_space,color_primaries -of csv=p=0 "$f"
    gh release upload mar-v3 "$f" --clobber
    rm -f orig.mp4 "$f"
  fi
  i=$((i+1))
done < lista.txt
