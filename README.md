# Audio Classifier — Assistive Sound Awareness App

An Android app designed to help deaf and hearing-impaired users understand their surrounding audio environment. The app listens to real-time audio, classifies it into sound categories, and indicates urgency level so users know what is happening around them.

---

## Features

- **Real-time Audio Classification** — Captures microphone input and classifies it on-device into categories like speech, music, alarms, and more
- **Urgency Scale** — Classifies sounds on a scale of Emergency, Ambient, and Loud so users can gauge the importance of surrounding sounds
- **Accessibility Focused** — Designed specifically for deaf and hearing-impaired users to improve sound awareness
- **YAMNet Model** — Powered by Google's YAMNet, capable of recognizing 521 sound classes
- **On-device Inference** — All classification runs locally using TensorFlow Lite, no internet required

---

## Sound Categories

| Category | Examples |
|---|---|
| Speech | Conversation, announcements |
| Music | Instruments, singing |
| Alarms & Alerts | Fire alarms, sirens |
| Ambient | Background noise, nature sounds |
| Loud Sounds | Explosions, crashes, horns |

---

## Urgency Scale

| Level | Description |
|---|---|
| 🔴 Emergency | Alarms, sirens, danger sounds — immediate attention needed |
| 🟡 Loud | High-intensity sounds that may require awareness |
| 🟢 Ambient | Background or non-critical sounds |

---

## Tech Stack

| Technology | Purpose |
|---|---|
| Kotlin | App development |
| Android SDK | Platform |
| TensorFlow Lite | On-device ML inference |
| YAMNet | Pre-trained audio classification model |
| Gradle | Build system |

---

## How It Works

1. App captures audio from the device microphone
2. Audio is fed into the YAMNet TFLite model
3. Model outputs a probability score across 521 sound classes
4. Sound is grouped into a category (speech, music, etc.)
5. Urgency level (Emergency / Loud / Ambient) is determined and displayed to the user in real-time

---

## Getting Started

### Requirements
- Android Studio
- Android device or emulator with API level 21+
- Microphone permission

### Steps

```bash
# Clone the repo
git clone https://github.com/Penquinz01/Mini_Project.git

# Open in Android Studio
# File > Open > Select the Mini_Project folder

# Build and run on a device or emulator
```

> ⚠️ Make sure to grant **microphone permission** when prompted.

---

## About YAMNet

YAMNet (Yet Another Mobile Network) is a pretrained deep neural network developed by Google that predicts 521 audio event classes from the AudioSet ontology. It uses a MobileNet v1 depthwise-separable convolution architecture and takes raw waveform audio as input.

---

## License

MIT
