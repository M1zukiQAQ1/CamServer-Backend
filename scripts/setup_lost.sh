#!/usr/bin/env bash
# Build a native LOST pair database on the machine that will use it.
set -euo pipefail
if [[ $# -ne 2 ]]; then
    echo "Usage: bash setup_lost.sh /path/to/lost-checkout /path/to/database-directory" >&2
    exit 2
fi
lost_checkout=$(cd "$1" && pwd)
mkdir -p "$2"
database_dir=$(cd "$2" && pwd)
if [[ ! -x "$lost_checkout/lost" ]]; then
    make -C "$lost_checkout" release LOST_DISABLE_ASAN=1
fi
database_tmp=$(mktemp "$database_dir/bright-stars.dat.XXXXXX")
trap 'rm -f "$database_tmp"' EXIT
(
    cd "$lost_checkout"
    ./lost database --max-stars 5000 --kvector \
        --kvector-min-distance 0.2 --kvector-max-distance 130 \
        --kvector-distance-bins 100000 --output "$database_tmp"
)
cp "$lost_checkout/bright-star-catalog.tsv" "$database_dir/bright-star-catalog.tsv"
mv "$database_tmp" "$database_dir/bright-stars.dat"
echo "LOST database ready: $database_dir/bright-stars.dat"
