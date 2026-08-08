# Xray-core Corresponding Source

Material Xray distributes unmodified official Linux and Android arm64 Xray-core executables.

- Version: `VERSION`
- Exact source commit: `COMMIT`
- Verified archive and executable hashes: `CHECKSUMS.sha256`
- Upstream source and releases: https://github.com/XTLS/Xray-core
- Xray-core license: MPL-2.0, reproduced in `LICENSE`

The pinned source tree's `go.mod` records dependency versions. The corresponding-source archive preserves each vendored module's source and license files.

Material Xray release pages attach an `Xray-core-<version>-source.tar.gz` archive containing the commit-pinned Xray-core source tree and vendored Go modules, including their original license files. `scripts/prepare-xray-source.sh` creates that archive.
