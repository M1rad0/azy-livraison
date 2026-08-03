#!/usr/bin/env bash
set -euo pipefail

dataset="${OSRM_DATASET:-madagascar-latest}"
threads="${OSRM_PREPARE_THREADS:-2}"
graph_version="${OSRM_GRAPH_VERSION:-main-roads-v1}"

case "$dataset" in
  ""|*[!a-zA-Z0-9._-]*)
    echo "OSRM_DATASET contains unsupported characters." >&2
    exit 2
    ;;
esac

pbf="/data/${dataset}.osm.pbf"
base="/data/${dataset}.osrm"
profile="/profiles/main_roads.lua"
marker="${base}.ready"

if [[ ! -f "$pbf" ]]; then
  echo "Missing OSM extract: $pbf" >&2
  exit 3
fi

source_hash="$(sha256sum "$pbf" | awk '{print $1}')"
profile_hash="$(sha256sum "$profile" | awk '{print $1}')"
fingerprint="${source_hash}:${profile_hash}:${graph_version}"

if [[ -f "$marker" \
      && -f "${base}.partition" \
      && -f "${base}.cell_metrics" \
      && "$(<"$marker")" == "$fingerprint" ]]; then
  echo "OSRM graph is already current for ${dataset}."
  exit 0
fi

echo "Preparing OSRM graph for ${dataset} with ${threads} threads."
find /data -maxdepth 1 -type f -name "${dataset}.osrm*" -delete

osrm-extract --threads "$threads" --profile "$profile" "$pbf"
osrm-partition --threads "$threads" "$base"
osrm-customize --threads "$threads" "$base"

printf '%s' "$fingerprint" > "$marker"
echo "OSRM graph ready: $base"
