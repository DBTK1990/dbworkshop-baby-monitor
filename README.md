# Android IP Camera

[![downloads](https://img.shields.io/github/downloads/DigitallyRefined/android-ip-camera/total.svg)](https://github.com/DigitallyRefined/android-ip-camera/releases)
[![downloads](https://img.shields.io/github/downloads/DigitallyRefined/android-ip-camera/latest/total.svg)](https://github.com/DigitallyRefined/android-ip-camera/releases)

An Android IP Camera app — WebRTC for ultra-low-latency video in the browser, RTSP for video app clients.

![Desktop Browser](screenshot.webp)

## Install

<div align="center">
<a href="https://github.com/DigitallyRefined/android-ip-camera/releases">
<img src="https://user-images.githubusercontent.com/69304392/148696068-0cfea65d-b18f-4685-82b5-329a330b1c0d.png"
alt="Get it on GitHub" align="center" height="80" /></a>

<a href="https://github.com/ImranR98/Obtainium">
<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/refs/heads/main/assets/graphics/badge_obtainium.png"
alt="Get it on Obtainium" align="center" height="54" /></a>
</div>

## Features

- 🌎 Built in web server — open `https://<ip_address>:4444` in a browser for a WebRTC live view with camera controls
- 📡 RTSP endpoint with RTP-over-TCP transport for clients that require `rtsp://` (using `rtsp://<username>:<password>@<ip_address>:8554/stream`)
- 🔗 go2rtc compatible — use `webrtc:https://user:pass@<ip>:4444/webrtc/offer` as a source; camera controls available via `https://<ip>:4444/?zoom=2&torch=on` etc.
- 📴 Option to turn the display off while streaming
- 🤳 Switch between the main or selfie camera
- 🎛️ Remote web interface with controls for camera selection, image rotation, flash light toggle, resolution, zoom, exposure and contrast
- 🖼️ Choose between different image quality settings
- 🛂 Username and password protection
- 🔐 Automatic TLS certificate support to protect stream and login details via HTTPS

## ⚠️ Warning

If you are planning to run this 24/7, please make sure that your phone does not stay at 100% charge. Doing so may damage the battery and cause it to swell up, which could cause it to explode.

Some models include an option to only charge to 80%, make sure this is enabled where possible.

Note: running at a higher image quality may cause some phones to over heat, which can also damage the battery.

## HTTPS/TLS certificates

To protect the stream and the password from being sent in plain-text over HTTP, a certificate can be used to start the stream over HTTPS.

The app will automatically generate a self-signed certificate on first launch, but if you have your own domain you can use [Let's Encrypt](https://letsencrypt.org) to generate a trusted certificate and skip the self-signed security warning message, by changing the TLS certificate in the settings.

To generate a new self-signed certificate, clear the app settings and restart or clone this repo and run `./scripts/generate-certificate.sh` then use the certificate `personal_certificate.p12` file it generates.
