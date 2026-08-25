#!/usr/bin/env bash
set -euo pipefail

adb shell dumpsys activity service com.material.xray/.service.XrayService
