# GlassDescribe - Setup Guide

## Prerequisites
- Android phone with the GlassDescribe app installed
- Laptop/PC with Node.js (v18+) installed
- USB-C cable OR same WiFi network (for wireless ADB)
- OpenAI API key

---

## Step 1: Set Up Backend

```bash
# Clone or download the backend
git clone -b backend https://github.com/SreeramRavi7/Altourism.git
cd Altourism

# Install dependencies
npm install

# Create .env file with your OpenAI API key
# IMPORTANT: Do NOT use Notepad to create this file (it saves as UTF-16)
# Use the command line instead:
echo OPENAI_API_KEY=sk-your-key-here > .env
echo PORT=3000 >> .env

# Start the server
node server.js
```

You should see:
```
=== GlassDescribe Server ===
Running on port 3000
```

**Keep this terminal open.** The server must stay running.

---

## Step 2: Connect Phone to Laptop

### Option A: USB Cable (Recommended)

1. Plug USB-C cable from phone to laptop
2. On your phone, tap **"Allow USB debugging"** when prompted
3. Open a **new terminal** (keep server running in the first one)
4. Run:

**Windows:**
```powershell
# If 'adb' works directly:
adb devices
adb reverse tcp:3000 tcp:3000

# If 'adb' is not recognized, use full path:
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse tcp:3000 tcp:3000
```

**Mac/Linux:**
```bash
adb devices
adb reverse tcp:3000 tcp:3000
```

5. `adb devices` should show your phone like:
```
List of devices attached
XXXXXXXX    device
```

6. `adb reverse` should say:
```
3000
```

### Option B: Wireless (No Cable)

1. Phone and laptop must be on the **same WiFi network**
2. On phone: Settings → Developer Options → Wireless Debugging → Turn ON
3. Tap **"Pair device with pairing code"** — note the IP, port, and code
4. On laptop:

```bash
adb pair 192.168.x.x:xxxxx
# Enter the pairing code when prompted

adb connect 192.168.x.x:xxxxx
adb reverse tcp:3000 tcp:3000
```

### Option C: WiFi Direct (No ADB)

If you can't use ADB at all, change the backend mode in `MainActivity.kt`:

```kotlin
private val backendMode = BackendMode.WIFI
private val laptopIp = "YOUR_LAPTOP_IP"  // e.g. "192.168.1.105"
```

Find your laptop's IP:
- **Windows:** Open terminal, run `ipconfig`, look for IPv4 Address
- **Mac:** System Preferences → Network → IP Address

Phone and laptop must be on the same WiFi.

---

## Step 3: Verify Connection

Open Chrome on your phone and go to:
```
http://127.0.0.1:3000/health
```

You should see:
```json
{"status":"ok","sessions":0,"features":["image-recognition","multilingual","translation","conversation-history"]}
```

If this works, the app will work.

---

## Step 4: Test the App

1. Open GlassDescribe on your phone
2. Tap **"Capture & Describe"** — it will use the phone camera
3. Point at something (a building, book, product, sign)
4. Wait for the AI response — it will speak the description
5. Try changing languages in the **"I speak"** and **"Respond in"** dropdowns

---

## Troubleshooting

### "Network error" in app
- Is `node server.js` running? Check the terminal.
- Did you run `adb reverse tcp:3000 tcp:3000`?
- Test: Open `http://127.0.0.1:3000/health` in phone Chrome

### `adb devices` shows nothing
- Unplug and replug the USB cable
- On phone: tap "Allow USB debugging" popup
- Make sure Developer Options is enabled:
  Settings → About Phone → Tap "Build Number" 7 times

### `adb` command not found
- Use full path: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"`
- Or add to PATH: Settings → Environment Variables → Add `C:\Users\YOU\AppData\Local\Android\Sdk\platform-tools`

### Server crashes with "Missing credentials"
- Your `.env` file is wrong. Delete it and recreate from command line:
  ```
  echo OPENAI_API_KEY=sk-your-key-here > .env
  ```
- Do NOT use Notepad (saves as UTF-16). Use command line or VS Code.

### Server shows "401 Unauthorized" or "429 Rate Limit"
- Your OpenAI API key is invalid or out of credits
- Check at: https://platform.openai.com/api-keys

### `adb reverse` says "error: no devices"
- Phone is not connected. Check USB cable and USB debugging.

---

## Architecture

```
Phone (GlassDescribe App)
    │
    │  HTTP POST /images (photo + language)
    │  HTTP POST /ask   (question + language)
    │
    ▼
Laptop (Node.js Backend)
    │
    │  OpenAI GPT-4o Vision API
    │
    ▼
OpenAI Cloud
    │
    │  AI description / translation
    │
    ▼
Phone (speaks response via TTS)
```

---

## Files

| File | Location | Purpose |
|------|----------|---------|
| `server.js` | Backend folder | Node.js server with GPT-4o |
| `.env` | Backend folder | OpenAI API key (DO NOT commit) |
| `MainActivity.kt` | Android app | Main app code |
| `google-services.json` | app/ folder | Firebase config (if using) |
| `local.properties` | Android project root | GitHub token for Meta SDK |

---

## Security

**Never commit these files:**
- `.env` (contains OpenAI API key)
- `local.properties` (contains GitHub token)
- `google-services.json` (contains Firebase config)

These should be in `.gitignore`.
