# Desk

Desk is a chat app for your phone. The model runs on your desktop PC, on the NVIDIA GPU you already have. The phone only shows the conversation.

```
Phone  --Wi-Fi or Tailscale-->  PC port 8787 (Desk)  -->  Ollama on port 11434  -->  GPU
```

Ollama is already installed on the PC. Desk does not install it. It checks that Ollama is answering, keeps the model loaded, and streams each token to the phone so the reply shows up as it is written.

## Get the code

```bash
git clone https://github.com/Strobingn/HomeAI.git
cd HomeAI
```

## What you need

- The desktop PC (Windows or Linux) with the NVIDIA GPU and Ollama already installed
- Python 3.11 or newer on that PC ([python.org](https://www.python.org/downloads/))
- An Android phone
- The phone and the PC on the same home Wi-Fi, or both signed into Tailscale

## 1. Download the model (once)

On the PC, open a terminal and run:

```bash
ollama pull qwen2.5:7b
```

That tag is Qwen2.5 7B Instruct at Q4_K_M, about 4.7 GB. It is the default because speed matters on a 12 GB card: the weights leave several gigabytes free for the chat cache, and a 7B model produces tokens quickly. Desk keeps it loaded in VRAM so the next reply does not wait on a cold start.

Other models you can switch to later:

| Model | When to use it |
| --- | --- |
| `qwen2.5:7b` | Default. Fastest good chat model for this PC. |
| `llama3.1:8b` | Another fast 8B model, if you prefer Llama. |
| `qwen2.5:14b` | Smarter, still fits in 12 GB at the default quant, but slower and tighter on long chats. |

Check that Ollama is using the GPU:

```bash
ollama ps
```

The processor column should say 100% GPU after the first reply. If it says CPU, the NVIDIA driver is not visible to Ollama.

## 2. Start Desk on the PC

Leave this window open. Closing it stops the phone from connecting.

**Linux**

```bash
./start.sh
```

**Windows**

Double-click `start.bat`. If Windows asks about a firewall, allow Python on private networks.

The first start installs the server into `server/.venv` and writes `config/.env`. If the API key was blank, the window prints a line like:

```
API_KEY=paste-this-into-the-phone
```

Copy that value. It is also stored in `config/.env` if you need it again. Do not commit that file or send it to anyone outside the house.

The window then prints the phone URL, for example:

```
Phone URL on this Wi-Fi:  http://192.168.1.20:8787
```

Use that exact address, including `http://` and the port.

You can confirm the server from the PC itself:

```bash
curl -N http://127.0.0.1:8787/chat \
  -H "Authorization: Bearer YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"messages\":[{\"role\":\"user\",\"content\":\"Say hello in five words.\"}]}"
```

Words should appear one piece at a time. `Ctrl+C` stops the curl command. It does not stop Desk.

## 3. Install the Android app

The project is a normal Gradle app. On a machine with the Android SDK:

```bash
cd android
./gradlew assembleDebug
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`.

Copy it to the phone (USB, email to yourself, or a shared folder). Open it. If Android blocks the install, allow installs for the app you used to open the file (Files, Chrome, or Gmail). The app is named **Desk**.

This build is a debug APK. Android may warn that it is from an unknown developer. That is expected.

## 4. Connect the phone

1. Open Desk.
2. Tap **Open Settings** (or the Settings tab).
3. Server URL: the Wi-Fi address from the Desk window, such as `http://192.168.1.20:8787`.
4. API key: the value after `API_KEY=` in `config/.env`. The Show button reveals what you typed.
5. Tap **Test connection**.

You want a green **Connected** card, and a line that says `qwen2.5:7b is loaded and ready.` The first test after a reboot can take a few seconds while the model loads into VRAM.

Then go Home and tap **New chat**. The reply streams onto the screen. Chats are stored on the phone.

You can always type the URL and key by hand. Settings does not depend on a QR code or automatic discovery.

## Use it away from home (Tailscale)

Do not open a port on your router.

1. Install Tailscale on the PC and the phone from [tailscale.com/download](https://tailscale.com/download).
2. Sign both in to the same Tailscale account.
3. Restart `start.sh` or `start.bat`. The window prints `Phone URL on Tailscale: http://100.x.x.x:8787`.
4. Put that address in the phone's Server URL. The API key stays the same.

If the window was already open, run `tailscale ip -4` on the PC and use `http://` plus that address plus `:8787`.

Desk listens on every interface (`0.0.0.0`), so the Tailscale address works without extra forwarding. Traffic stays inside your tailnet.

## Start Desk when you sign in

Run `start.sh` or `start.bat` once by hand first, so the API key and the Python environment exist.

**Windows**

1. Press Win+R, type `shell:startup`, and press Enter.
2. Right-click inside that folder, choose New, then Shortcut.
3. Point the shortcut at `start.bat` in this project.
4. Sign out and back in. Leave the Desk window open.

**Linux**

Create `~/.config/systemd/user/homedesk.service` and replace both paths:

```ini
[Unit]
Description=Home Desk
After=network-online.target

[Service]
WorkingDirectory=/home/YOU/desk/server
ExecStart=/home/YOU/desk/server/.venv/bin/python -m app.main
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

Then:

```bash
systemctl --user daemon-reload
systemctl --user enable --now homedesk.service
```

Ollama's own app should also start when you sign in. Desk only talks to it.

The model stays in GPU memory after you close Desk, because Ollama was asked to keep it loaded (`KEEP_ALIVE=-1`). The next start is then instant. To free the GPU:

```bash
ollama stop qwen2.5:7b
```

Set the PC so it does not go to sleep while you want the phone to reach it. A sleeping PC will not answer.

## Change the model

In the phone: open a chat, tap the model name at the top, or use Settings. Pick a model from the list, or type a name and tap **Use this model**. Typing a name always works, even when the list has not loaded.

That choice is saved on the phone and sent to the PC, which loads it and keeps it warm. The PC keeps one model loaded so replies stay fast.

You can also change the default before the first start by editing `DEFAULT_MODEL` in `config/.env`. `NUM_CTX` is the context length. `4096` is the speed setting. Raise it if long chats start to forget the beginning.

## Troubleshooting

**The phone says it can't reach the desk.** The Desk window must still be open. The URL must start with `http://` and include `:8787`. The phone must be on the same Wi-Fi as the PC, or on Tailscale. Some guest networks block phones from seeing computers. Use the main Wi-Fi.

**API key was rejected.** Copy the value after `API_KEY=` again. Do not include the quotes or the name `API_KEY`.

**Ollama is not running.** Open the Ollama app, or run `ollama serve`. Desk does not install Ollama.

**The model is not downloaded.** Run `ollama pull qwen2.5:7b` on the PC.

**Port already in use.** Another Desk window is open. Close it, or set a different `PORT` in `config/.env` and use that port in the phone URL.

**Windows firewall.** Allow Python on private networks. If you dismissed the prompt:

```bat
netsh advfirewall firewall add rule name="Home Desk" dir=in action=allow protocol=TCP localport=8787
```

**Linux says ensurepip is not available.** Install the venv package, then run `./start.sh` again. On Ubuntu: `sudo apt install python3-venv`.

**Linux firewall**, only if you use ufw:

```bash
sudo ufw allow 8787/tcp
```

## How the server is built

The gateway is Python and FastAPI. A small process is simpler to start on both Windows and Linux than a compiled binary, and the GPU is what limits reply speed. The server streams Server-Sent Events. The phone uses OkHttp with a connection pool so the next message does not pay for a new handshake.

Every chat request sends Ollama `keep_alive: -1`, and startup loads the selected model with an empty message list (weights only, no extra generation).

Endpoints, all except `GET /` require `Authorization: Bearer <API_KEY>` (or `X-API-Key`):

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | Plain check that the process is up |
| GET | `/health` | Ollama, selected model, whether it is loaded |
| GET | `/models` | Models Ollama has downloaded |
| GET | `/model` | The model Desk will keep warm |
| PUT | `/model` | Select a model and load it |
| POST | `/chat` | Stream a reply as `text/event-stream` |

## Tests

From the `server` folder, with Python 3.11+:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt -r requirements-dev.txt
.venv/bin/pytest
```

On Windows, use `server\.venv\Scripts\python` and `server\.venv\Scripts\pytest` the same way.

The tests stand in for Ollama, so they do not need a GPU. Android unit tests run with:

```bash
cd android
./gradlew test assembleDebug
```
